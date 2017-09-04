package net.kundzi.socket.channels.server;

import net.kundzi.socket.channels.io.MessageReader;
import net.kundzi.socket.channels.io.MessageWriter;
import net.kundzi.socket.channels.io.lvmessage.LvMessage;
import net.kundzi.socket.channels.io.lvmessage.LvMessageReader;
import net.kundzi.socket.channels.io.lvmessage.LvMessageWriter;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static java.lang.System.out;
import static java.util.stream.Collectors.toList;

public class SimpleReactorServer {

  class MessageEvent {
    final LvMessage message;
    final ClientConnection from;

    MessageEvent(final LvMessage message, final ClientConnection from) {
      this.message = message;
      this.from = from;
    }
  }

  public SimpleReactorServer(final InetSocketAddress bindAddress,
                             final MessagesListener messagesListener) {
    this.bindAddress = Objects.requireNonNull(bindAddress);
    this.messagesListener = Objects.requireNonNull(messagesListener);
  }

  enum State {
    NOT_STARTED,
    STARTED,
    STOPPED
  }

  public interface MessagesListener {
    void onMessage(LvMessage message, ClientConnection from);
  }

  private final InetSocketAddress bindAddress;
  private final ExecutorService selectExecutor = Executors.newSingleThreadExecutor();
  private final ExecutorService incomingMessagesDeliveryExecutor = Executors.newSingleThreadExecutor();
  private final ExecutorService outgoingMessagesDeliveryExecutor = Executors.newSingleThreadExecutor();
  private final ScheduledExecutorService reaper = Executors.newSingleThreadScheduledExecutor();

  private final AtomicReference<State> state = new AtomicReference<>(State.NOT_STARTED);
  private final CopyOnWriteArrayList<ClientConnection> clients = new CopyOnWriteArrayList<>();
  private Selector selector;
  private ServerSocketChannel boundServerChannel;

  private final MessageReader<LvMessage> messageReader = new LvMessageReader();
  private final MessageWriter<LvMessage> messageWriter = new LvMessageWriter();
  private final MessagesListener messagesListener;

  public void start() throws IOException {
    selector = Selector.open();
    boundServerChannel = ServerSocketChannel.open().bind(bindAddress);
    boundServerChannel.configureBlocking(false);
    boundServerChannel.register(selector, SelectionKey.OP_ACCEPT, null);

    selectExecutor.execute(this::loop);
    state.set(State.STARTED);

    reaper.scheduleAtFixedRate(this::harvestDeadConnections, 100, 100, TimeUnit.MILLISECONDS);
  }

  private void harvestDeadConnections() {
    if (isNotStopped()) {
      final List<ClientConnection> deadConnections = clients.stream()
          .filter(ClientConnection::isMarkedDead)
          .collect(toList());

      if (deadConnections.isEmpty()) {
        return;
      }

      out.println("Harvested clients: " + deadConnections.size());
      deadConnections.stream().forEach(clientConnection -> {
        try {
          out.println("removing: " + clientConnection.getSocketChannel().getRemoteAddress());
          clientConnection.unregister();
          clientConnection.getSocketChannel().close();
        } catch (IOException e) {
        }
      });
      clients.removeAll(deadConnections);
    }
  }

  public void stop() {
    state.set(State.STOPPED);
    try {
      selector.close();
    } catch (IOException e) {
    }
    getClients().forEach(client -> {
      try {
        out.println("server closing :" + client.getRemoteAddress());
        client.unregister();
        client.getSocketChannel().close();
        out.println("server closed :" + client.getRemoteAddress());
      } catch (IOException e) {
        e.printStackTrace();
      }
    });
    selectExecutor.shutdown();
    incomingMessagesDeliveryExecutor.shutdown();
    outgoingMessagesDeliveryExecutor.shutdown();
    reaper.shutdown();
  }

  public void join() {
    if (state.get() != State.STARTED) {
      return;
    }
    try {
      selectExecutor.awaitTermination(Integer.MAX_VALUE, TimeUnit.DAYS);
      incomingMessagesDeliveryExecutor.awaitTermination(Integer.MAX_VALUE, TimeUnit.DAYS);
      outgoingMessagesDeliveryExecutor.awaitTermination(Integer.MAX_VALUE, TimeUnit.DAYS);
      reaper.awaitTermination(Integer.MAX_VALUE, TimeUnit.DAYS);
    } catch (InterruptedException e) {
      e.printStackTrace();
    }
  }

  public List<ClientConnection> getClients() {
    return Collections.unmodifiableList(clients);
  }

  private void loop() {
    try {
      _loop();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void _loop() throws IOException {
    while (isNotStopped()) {
      final int numSelected = selector.select();
      if (0 == numSelected) {
        continue;
      }

      final Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
      while (isNotStopped() && iterator.hasNext()) {
        final SelectionKey key = iterator.next();
        final ArrayList<MessageEvent> newMessages = new ArrayList<>(numSelected);

        try {
          if (key.isAcceptable()) {
            final SocketChannel socketChannel = boundServerChannel.accept();
            final ClientConnection newClient = new ClientConnection(socketChannel);
            onAccepting(newClient);
          }

          if (key.isReadable()) {
            final ClientConnection client = (ClientConnection) key.attachment();
            onReading(client).ifPresent(message -> newMessages.add(new MessageEvent(message, client)));
          }

          if (key.isWritable()) {
            onWriting((ClientConnection) key.attachment());
          }

          deliverNewMessages(newMessages);
        } catch (CancelledKeyException e) {
          e.printStackTrace();
          // carry on
        } finally {
          iterator.remove();
        }
      }
    }

  }

  private void sendOutgoingMessages(ClientConnection clientConnection) {
    if (clientConnection.getNumberOfOutgoingMessages() == 0) {
      return;
    }
    outgoingMessagesDeliveryExecutor.execute(() -> {
      try {
        int numSent = 0;
        int pending = clientConnection.getNumberOfOutgoingMessages();
        if (clientConnection.getSocketChannel().isConnected()) {
          final LvMessage outgoingMessage = clientConnection.getOutgoingMessages().poll();
          if (outgoingMessage != null) {
            try {
              messageWriter.write(clientConnection.getSocketChannel(), outgoingMessage);
              numSent++;
            } catch (IOException e) {
            }
          }
        }
        if (numSent + pending != 0) {
          out.println("Sent=" + numSent +
                          " pending=" + pending +
                          " addr=" + clientConnection.getRemoteAddress());
        }
      } catch (IOException e) {
        e.printStackTrace();
      }
    });
  }

  private boolean isNotStopped() {
    return state.get() != State.STOPPED;
  }

  void onAccepting(ClientConnection client) throws IOException {
    client.getSocketChannel().configureBlocking(false);
    client.register(selector);
    clients.add(client);
  }

  Optional<LvMessage> onReading(ClientConnection client) {
    try {
      final LvMessage message = messageReader.read(client.getSocketChannel());
      return Optional.of(message);
    } catch (IOException e) {
      client.markDead();
      return Optional.empty();
    }
  }

  void onWriting(ClientConnection client) {
    sendOutgoingMessages(client);
  }

  private void deliverNewMessages(final ArrayList<MessageEvent> newMessages) {
    incomingMessagesDeliveryExecutor.execute(() -> {
      for (final MessageEvent newMessage : newMessages) {
        try {
          messagesListener.onMessage(newMessage.message, newMessage.from);
        } catch (Exception e) {
          // TODO better logging here
          e.printStackTrace();
        }
      }
    });
  }

}