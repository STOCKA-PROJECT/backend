package com.stocka.backend.modules.locations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.locations.entity.Location;

@DisplayName("LocationCycleValidator")
class LocationCycleValidatorTest {

    private final LocationCycleValidator sut = new LocationCycleValidator();

    private static Location location(int id, Location parent) {
        return new Location().setId(id).setName("loc-" + id).setParent(parent);
    }

    @Test
    @DisplayName("should pass when candidate parent is null (move to root)")
    void should_pass_when_candidateParentNull() {
        Location subject = location(1, null);
        sut.ensureNoCycle(subject, null);
    }

    @Test
    @DisplayName("should pass when candidate parent is unrelated")
    void should_pass_when_candidateParentNotAncestor() {
        Location root = location(1, null);
        Location other = location(2, null);
        sut.ensureNoCycle(other, root);
    }

    @Test
    @DisplayName("should throw 400 when candidate parent is the subject itself")
    void should_throw_when_candidateParentIsSelf() {
        Location subject = location(1, null);
        assertThatThrownBy(() -> sut.ensureNoCycle(subject, subject))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("should throw when candidate parent is a direct child")
    void should_throw_when_candidateParentIsDirectChild() {
        Location subject = location(1, null);
        Location child = location(2, subject);
        assertThatThrownBy(() -> sut.ensureNoCycle(subject, child))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("should throw when candidate parent is a deep descendant")
    void should_throw_when_candidateParentIsDeepDescendant() {
        Location subject = location(1, null);
        Location child = location(2, subject);
        Location grandchild = location(3, child);
        Location greatGrandchild = location(4, grandchild);
        assertThatThrownBy(() -> sut.ensureNoCycle(subject, greatGrandchild))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    @DisplayName("should pass when subject has no id yet (creation)")
    void should_pass_when_subjectIdNull() {
        Location subject = new Location().setName("new");
        Location parent = location(2, null);
        sut.ensureNoCycle(subject, parent);
        assertThat(subject.getParent()).isNull();
    }
}
