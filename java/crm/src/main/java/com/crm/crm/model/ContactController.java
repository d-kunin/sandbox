package com.crm.crm.model;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.net.URISyntaxException;
import java.util.Collection;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "http://localhost:3000")
public class ContactController {

    private ContactRepository contactRepository;

    @Autowired
    public ContactController(ContactRepository contactRepository) {
        this.contactRepository = contactRepository;
    }

    @GetMapping("/contacts")
    Collection<Contact> contacts() {
        return (Collection<Contact>) contactRepository.findAll();
    }

    @PostMapping("/contacts")
    ResponseEntity<Contact> createContact(
            @Valid @RequestBody Contact contact
    ) throws URISyntaxException {
        Contact result = contactRepository.save(contact);
        return ResponseEntity.ok().body(result);
    }
}