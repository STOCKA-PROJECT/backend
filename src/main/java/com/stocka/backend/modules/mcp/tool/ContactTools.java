package com.stocka.backend.modules.mcp.tool;

import com.stocka.backend.modules.common.schema.Param;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.stocka.backend.modules.contacts.dto.ContactResponseDto;
import com.stocka.backend.modules.contacts.dto.CreateContactDto;
import com.stocka.backend.modules.contacts.dto.LinkContactDto;
import com.stocka.backend.modules.contacts.dto.UpdateContactDto;
import com.stocka.backend.modules.contacts.entity.Contact;
import com.stocka.backend.modules.contacts.service.ContactService;
import com.stocka.backend.modules.mcp.tool.support.OrgAccess;
import com.stocka.backend.modules.oauth.OAuthScope;
import com.stocka.backend.modules.organizations.entity.Organization;

/**
 * Contacts: piece owners who are not Stocka users.
 */
@Component
public class ContactTools implements ToolSource {

    public record ListInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param("Text to match against name, last name and email") String q) {
    }

    public record ContactsResult(@Param("Contacts") List<ContactResponseDto> items) {
    }

    public record CreateInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "First name", required = true) String name,
            @Param("Last name") String lastName,
            @Param("Email") String email,
            @Param("Phone") String phone,
            @Param("Notes") String notes) {
    }

    public record UpdateInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Contact id", required = true) Integer contactId,
            @Param("New first name") String name,
            @Param("New last name") String lastName,
            @Param("New email") String email,
            @Param("New phone") String phone,
            @Param("New notes") String notes) {
    }

    public record ContactInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Contact id", required = true) Integer contactId) {
    }

    public record LinkInput(
            @Param(value = "Organization slug", required = true) String org,
            @Param(value = "Contact id", required = true) Integer contactId,
            @Param(value = "Stocka user id to link the contact to", required = true) Integer userId,
            @Param("Move the contact's pieces to the user (default false)") Boolean migratePieces) {
    }

    public record LinkResult(
            @Param("The contact") ContactResponseDto contact,
            @Param("Pieces moved to the user") int migratedPieces) {
    }

    private final OrgAccess access;
    private final ContactService contacts;

    public ContactTools(OrgAccess access, ContactService contacts) {
        this.access = access;
        this.contacts = contacts;
    }

    @Override
    public List<ToolDefinition<?>> tools() {
        return List.of(
                ToolDefinition.of("contacts_list",
                        "Contacts of the organization (owners who are not Stocka users), optionally filtered by text.",
                        ListInput.class, OAuthScope.READ, ToolHints.READ, this::list).returning(ContactsResult.class),
                ToolDefinition.of("contact_create",
                        "Creates a contact. Needs OWNER, MANAGER or USER.",
                        CreateInput.class, OAuthScope.INVENTORY_WRITE, ToolHints.CREATE, this::create)
                        .returning(ContactResponseDto.class),
                ToolDefinition.of("contact_update",
                        "Changes a contact's details. Needs OWNER or MANAGER.",
                        UpdateInput.class, OAuthScope.INVENTORY_WRITE, ToolHints.UPDATE, this::update)
                        .returning(ContactResponseDto.class),
                ToolDefinition.of("contact_delete",
                        "Deletes a contact that owns no pieces. Needs OWNER or MANAGER and the delete scope.",
                        ContactInput.class, OAuthScope.DELETE, ToolHints.DELETE, this::delete)
                        .returning(ToolResults.Done.class),
                ToolDefinition.of("contact_link",
                        "Links a contact to a Stocka user, optionally moving the contact's pieces to that user. "
                                + "Needs OWNER or MANAGER.",
                        LinkInput.class, OAuthScope.INVENTORY_WRITE, ToolHints.UPDATE, this::link)
                        .returning(LinkResult.class),
                ToolDefinition.of("contact_unlink",
                        "Removes the link between a contact and a user. Needs OWNER or MANAGER.",
                        ContactInput.class, OAuthScope.INVENTORY_WRITE, ToolHints.UPDATE, this::unlink)
                        .returning(ContactResponseDto.class));
    }

    private ContactsResult list(ToolCall<ListInput> call) {
        Organization org = access.read(call.caller(), call.input().org());
        List<ContactResponseDto> out = new ArrayList<>();
        for (Contact contact : contacts.listAll(org.getId(), call.input().q())) {
            out.add(ContactResponseDto.from(contact));
        }
        return new ContactsResult(out);
    }

    private ContactResponseDto create(ToolCall<CreateInput> call) {
        Organization org = access.writePieces(call.caller(), call.input().org());
        CreateContactDto dto = new CreateContactDto();
        dto.setName(call.input().name());
        dto.setLastName(call.input().lastName());
        dto.setEmail(call.input().email());
        dto.setPhone(call.input().phone());
        dto.setNotes(call.input().notes());
        return ContactResponseDto.from(contacts.create(org.getId(), dto));
    }

    private ContactResponseDto update(ToolCall<UpdateInput> call) {
        Organization org = access.manage(call.caller(), call.input().org());
        UpdateContactDto dto = new UpdateContactDto();
        dto.setName(call.input().name());
        dto.setLastName(call.input().lastName());
        dto.setEmail(call.input().email());
        dto.setPhone(call.input().phone());
        dto.setNotes(call.input().notes());
        return ContactResponseDto.from(contacts.update(org.getId(), call.input().contactId(), dto));
    }

    private ToolResults.Done delete(ToolCall<ContactInput> call) {
        Organization org = access.manage(call.caller(), call.input().org());
        contacts.softDelete(org.getId(), call.input().contactId());
        return ToolResults.Done.of("Contact " + call.input().contactId() + " deleted");
    }

    private LinkResult link(ToolCall<LinkInput> call) {
        Organization org = access.manage(call.caller(), call.input().org());
        LinkContactDto dto = new LinkContactDto();
        dto.setUserId(call.input().userId());
        dto.setMigratePieces(call.input().migratePieces());
        int migrated = contacts.link(org.getId(), call.input().contactId(), dto);
        Contact contact = contacts.findInOrg(org.getId(), call.input().contactId());
        return new LinkResult(ContactResponseDto.from(contact), migrated);
    }

    private ContactResponseDto unlink(ToolCall<ContactInput> call) {
        Organization org = access.manage(call.caller(), call.input().org());
        return ContactResponseDto.from(contacts.unlink(org.getId(), call.input().contactId()));
    }
}
