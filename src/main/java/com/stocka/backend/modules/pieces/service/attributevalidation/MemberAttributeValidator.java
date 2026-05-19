package com.stocka.backend.modules.pieces.service.attributevalidation;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationMember;
import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;
import com.stocka.backend.modules.organizations.repository.OrganizationMemberRepository;
import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

/**
 * Validates a {@code MEMBER} attribute value. The raw value is the {@code id} of the selected
 * user. The user must exist, be an active member of the organization that owns the piece and
 * carry a role included in {@code rules.eligibleRoles} (when configured).
 *
 * <p>Returns the user id as a trimmed decimal string so the canonical representation matches the
 * "string in / string out" contract of the rest of the validators.
 */
@Component
public class MemberAttributeValidator extends AbstractAttributeValueValidator {

    private final UserRepository userRepository;
    private final OrganizationMemberRepository memberRepository;

    public MemberAttributeValidator(UserRepository userRepository,
                                    OrganizationMemberRepository memberRepository) {
        this.userRepository = userRepository;
        this.memberRepository = memberRepository;
    }

    @Override
    public AttributeType supports() {
        return AttributeType.MEMBER;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The MEMBER validator requires organization context to resolve membership and role.
     * Invoking this organization-agnostic overload is a programming error and always throws
     * {@link IllegalStateException}.
     */
    @Override
    public String validateAndNormalize(String raw, AttributeValidatorsDto rules, boolean required) {
        throw new IllegalStateException(
                "MemberAttributeValidator requires organization context; call the 4-arg overload");
    }

    @Override
    public String validateAndNormalize(String raw, AttributeValidatorsDto rules, boolean required,
                                       Organization organization) {
        String checked = preCheck(raw, required);
        if (checked == null) return null;

        Integer userId = parseUserId(checked.trim());
        User user = userRepository.findById(userId)
                .orElseThrow(() -> badRequest("El usuario seleccionado no existe"));

        OrganizationMember membership = memberRepository.findByUserAndOrganization(user, organization)
                .orElseThrow(() -> badRequest("El usuario no es miembro de la organización"));

        Set<OrganizationRoleEnum> allowed = parseEligibleRoles(rules.getEligibleRoles());
        if (!allowed.contains(membership.getRole())) {
            throw badRequest("El rol del usuario no es elegible para este atributo");
        }

        return userId.toString();
    }

    private Integer parseUserId(String value) {
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            throw badRequest("El valor debe ser un identificador de usuario válido");
        }
    }

    /**
     * Resolves the configured allow-list of org roles. An absent or empty list means
     * "any role is acceptable" so we return all roles. Unknown values are rejected with a 500
     * since they indicate a server-side configuration error (the validators blob is written by
     * us, not by the client).
     */
    private Set<OrganizationRoleEnum> parseEligibleRoles(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return EnumSet.allOf(OrganizationRoleEnum.class);
        }
        Set<OrganizationRoleEnum> resolved = EnumSet.noneOf(OrganizationRoleEnum.class);
        for (String value : raw) {
            try {
                resolved.add(OrganizationRoleEnum.valueOf(value));
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Rol elegible desconocido: " + value);
            }
        }
        return resolved;
    }
}
