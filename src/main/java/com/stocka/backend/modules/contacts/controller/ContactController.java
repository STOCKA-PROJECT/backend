package com.stocka.backend.modules.contacts.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stocka.backend.modules.contacts.dto.ContactResponseDto;
import com.stocka.backend.modules.contacts.dto.CreateContactDto;
import com.stocka.backend.modules.contacts.dto.LinkContactDto;
import com.stocka.backend.modules.contacts.dto.LinkContactResponseDto;
import com.stocka.backend.modules.contacts.dto.UpdateContactDto;
import com.stocka.backend.modules.contacts.entity.Contact;
import com.stocka.backend.modules.contacts.service.ContactService;
import com.stocka.backend.modules.organizations.service.OrganizationResolver;

/**
 * REST resource exposing an organization's contact directory (external people that can own pieces
 * without being organization members).
 *
 * <p>Reading is allowed to any member (including SPECTATOR, who needs the names to render owners).
 * Creating is open to every writing role (OWNER/MANAGER/USER) so a contact can be added on the fly
 * while creating a piece. Editing, deleting and linking are directory management and require
 * OWNER or MANAGER.
 */
@RestController
@RequestMapping("/organizations/{orgSlug}/contacts")
public class ContactController {
    private final ContactService contactService;
    private final OrganizationResolver orgResolver;

    public ContactController(ContactService contactService, OrganizationResolver orgResolver) {
        this.contactService = contactService;
        this.orgResolver = orgResolver;
    }

    @GetMapping
    @PreAuthorize("@orgSecurity.canReadOrgContent(#orgSlug, principal)")
    public ResponseEntity<List<ContactResponseDto>> list(
            @PathVariable String orgSlug,
            @RequestParam(required = false) String q
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        List<ContactResponseDto> out = contactService.listAll(orgId, q).stream()
                .map(ContactResponseDto::from)
                .toList();
        return ResponseEntity.ok(out);
    }

    @PostMapping
    @PreAuthorize("@orgSecurity.canWritePieces(#orgSlug, principal)")
    public ResponseEntity<ContactResponseDto> add(
            @PathVariable String orgSlug,
            @RequestBody CreateContactDto dto
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        Contact contact = contactService.create(orgId, dto);
        return ResponseEntity.status(HttpStatus.CREATED).body(ContactResponseDto.from(contact));
    }

    @PatchMapping("/{contactId}")
    @PreAuthorize("@orgSecurity.canManageOrgContent(#orgSlug, principal)")
    public ResponseEntity<ContactResponseDto> update(
            @PathVariable String orgSlug,
            @PathVariable Integer contactId,
            @RequestBody UpdateContactDto dto
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        Contact contact = contactService.update(orgId, contactId, dto);
        return ResponseEntity.ok(ContactResponseDto.from(contact));
    }

    @DeleteMapping("/{contactId}")
    @PreAuthorize("@orgSecurity.canManageOrgContent(#orgSlug, principal)")
    public ResponseEntity<Void> delete(
            @PathVariable String orgSlug,
            @PathVariable Integer contactId
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        contactService.softDelete(orgId, contactId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{contactId}/link")
    @PreAuthorize("@orgSecurity.canManageOrgContent(#orgSlug, principal)")
    public ResponseEntity<LinkContactResponseDto> link(
            @PathVariable String orgSlug,
            @PathVariable Integer contactId,
            @RequestBody LinkContactDto dto
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        int migrated = contactService.link(orgId, contactId, dto);
        Contact contact = contactService.findInOrg(orgId, contactId);
        return ResponseEntity.ok(new LinkContactResponseDto(ContactResponseDto.from(contact), migrated));
    }

    @DeleteMapping("/{contactId}/link")
    @PreAuthorize("@orgSecurity.canManageOrgContent(#orgSlug, principal)")
    public ResponseEntity<ContactResponseDto> unlink(
            @PathVariable String orgSlug,
            @PathVariable Integer contactId
    ) {
        Integer orgId = orgResolver.requireCurrent(orgSlug).getId();
        Contact contact = contactService.unlink(orgId, contactId);
        return ResponseEntity.ok(ContactResponseDto.from(contact));
    }
}
