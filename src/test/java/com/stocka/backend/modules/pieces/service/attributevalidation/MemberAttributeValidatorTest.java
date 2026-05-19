package com.stocka.backend.modules.pieces.service.attributevalidation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationMember;
import com.stocka.backend.modules.organizations.entity.OrganizationRoleEnum;
import com.stocka.backend.modules.organizations.repository.OrganizationMemberRepository;
import com.stocka.backend.modules.piecetypes.dto.AttributeValidatorsDto;
import com.stocka.backend.modules.piecetypes.entity.AttributeType;
import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.repository.UserRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("MemberAttributeValidator")
class MemberAttributeValidatorTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private OrganizationMemberRepository memberRepository;

    @InjectMocks
    private MemberAttributeValidator sut;

    private Organization organization;
    private User user;

    @BeforeEach
    void setUp() {
        organization = new Organization().setId(7);
        user = new User().setId(42);
    }

    private static AttributeValidatorsDto rules() {
        return new AttributeValidatorsDto();
    }

    private static AttributeValidatorsDto rulesWith(OrganizationRoleEnum... allowed) {
        return new AttributeValidatorsDto()
                .setEligibleRoles(java.util.Arrays.stream(allowed).map(Enum::name).toList());
    }

    private static OrganizationMember member(User user, Organization org, OrganizationRoleEnum role) {
        return new OrganizationMember()
                .setUser(user)
                .setOrganization(org)
                .setRole(role);
    }

    @Test
    void should_reportType_MEMBER() {
        assertThat(sut.supports()).isEqualTo(AttributeType.MEMBER);
    }

    @Test
    void should_returnNull_when_optionalAndBlank() {
        assertThat(sut.validateAndNormalize("  ", rules(), false, organization)).isNull();
    }

    @Test
    void should_throw400_when_requiredAndBlank() {
        assertThatThrownBy(() -> sut.validateAndNormalize("", rules(), true, organization))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void should_throw400_when_valueNotNumeric() {
        assertThatThrownBy(() -> sut.validateAndNormalize("alice", rules(), true, organization))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void should_throw400_when_userNotFound() {
        when(userRepository.findById(eq(42))).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.validateAndNormalize("42", rules(), true, organization))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void should_throw400_when_userIsNotMember() {
        when(userRepository.findById(eq(42))).thenReturn(Optional.of(user));
        when(memberRepository.findByUserAndOrganization(user, organization)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> sut.validateAndNormalize("42", rules(), true, organization))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void should_throw400_when_userRoleIsNotEligible() {
        when(userRepository.findById(eq(42))).thenReturn(Optional.of(user));
        when(memberRepository.findByUserAndOrganization(user, organization))
                .thenReturn(Optional.of(member(user, organization, OrganizationRoleEnum.SPECTATOR)));
        AttributeValidatorsDto r = rulesWith(OrganizationRoleEnum.MANAGER, OrganizationRoleEnum.USER);

        assertThatThrownBy(() -> sut.validateAndNormalize("42", r, true, organization))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void should_returnUserId_when_userMatchesEligibleRole() {
        when(userRepository.findById(eq(42))).thenReturn(Optional.of(user));
        when(memberRepository.findByUserAndOrganization(user, organization))
                .thenReturn(Optional.of(member(user, organization, OrganizationRoleEnum.MANAGER)));
        AttributeValidatorsDto r = rulesWith(OrganizationRoleEnum.MANAGER, OrganizationRoleEnum.USER);

        assertThat(sut.validateAndNormalize(" 42 ", r, true, organization)).isEqualTo("42");
    }

    @Test
    void should_acceptAnyRole_when_eligibleRolesIsEmpty() {
        when(userRepository.findById(eq(42))).thenReturn(Optional.of(user));
        when(memberRepository.findByUserAndOrganization(user, organization))
                .thenReturn(Optional.of(member(user, organization, OrganizationRoleEnum.SPECTATOR)));

        assertThat(sut.validateAndNormalize("42", rules(), true, organization)).isEqualTo("42");
    }

    @Test
    void should_acceptAnyRole_when_eligibleRolesIsExplicitlyEmptyList() {
        when(userRepository.findById(eq(42))).thenReturn(Optional.of(user));
        when(memberRepository.findByUserAndOrganization(user, organization))
                .thenReturn(Optional.of(member(user, organization, OrganizationRoleEnum.OWNER)));
        AttributeValidatorsDto r = new AttributeValidatorsDto().setEligibleRoles(List.of());

        assertThat(sut.validateAndNormalize("42", r, true, organization)).isEqualTo("42");
    }

    @Test
    void should_throw500_when_eligibleRoleValueIsUnknown() {
        AttributeValidatorsDto r = new AttributeValidatorsDto().setEligibleRoles(List.of("BOSS"));
        when(userRepository.findById(eq(42))).thenReturn(Optional.of(user));
        when(memberRepository.findByUserAndOrganization(user, organization))
                .thenReturn(Optional.of(member(user, organization, OrganizationRoleEnum.MANAGER)));

        assertThatThrownBy(() -> sut.validateAndNormalize("42", r, true, organization))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void should_throwIllegalState_when_legacyEntrypointInvoked() {
        assertThatThrownBy(() -> sut.validateAndNormalize("42", rules(), true))
                .isInstanceOf(IllegalStateException.class);
    }
}
