package com.stocka.backend.modules.search.listener;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.stocka.backend.modules.notifications.events.ResourceKind;
import com.stocka.backend.modules.notifications.events.ResourceLifecycleEvent;
import com.stocka.backend.modules.notifications.preferences.entity.LifecycleAction;
import com.stocka.backend.modules.search.SemanticSearchProperties;
import com.stocka.backend.modules.search.client.XeyeException;
import com.stocka.backend.modules.search.service.SemanticSearchIndexer;

@ExtendWith(MockitoExtension.class)
@DisplayName("PieceLifecycleSearchListener")
class PieceLifecycleSearchListenerTest {

    @Mock private SemanticSearchIndexer indexer;

    private final SemanticSearchProperties properties = new SemanticSearchProperties();
    private PieceLifecycleSearchListener sut;

    @BeforeEach
    void setUp() {
        properties.setEnabled(true);
        properties.setApiKey("k");
        sut = new PieceLifecycleSearchListener(properties, indexer);
    }

    private static ResourceLifecycleEvent event(ResourceKind kind, LifecycleAction action) {
        return new ResourceLifecycleEvent(3, kind, action, 42, "Pastor", 1, 1);
    }

    @Test
    @DisplayName("indexes a created piece")
    void created() {
        sut.onResourceLifecycle(event(ResourceKind.PIECE, LifecycleAction.CREATED));
        verify(indexer).index(42);
    }

    @Test
    @DisplayName("re-indexes an edited piece")
    void edited() {
        sut.onResourceLifecycle(event(ResourceKind.PIECE, LifecycleAction.EDITED));
        verify(indexer).index(42);
    }

    @Test
    @DisplayName("removes a deleted piece")
    void deleted() {
        sut.onResourceLifecycle(event(ResourceKind.PIECE, LifecycleAction.DELETED));
        verify(indexer).remove(42);
    }

    @Test
    @DisplayName("ignores other resource kinds")
    void otherKinds() {
        sut.onResourceLifecycle(event(ResourceKind.LOCATION, LifecycleAction.CREATED));
        sut.onResourceLifecycle(event(ResourceKind.PIECE_TYPE, LifecycleAction.DELETED));
        verifyNoInteractions(indexer);
    }

    @Test
    @DisplayName("ignores events when the feature is not configured")
    void notConfigured() {
        properties.setEnabled(false);
        sut.onResourceLifecycle(event(ResourceKind.PIECE, LifecycleAction.CREATED));
        verifyNoInteractions(indexer);
    }

    @Test
    @DisplayName("swallows indexing failures so the request thread never sees them")
    void swallowsFailures() {
        doThrow(new XeyeException(503, "SERVICE_UNAVAILABLE", "down")).when(indexer).index(42);
        sut.onResourceLifecycle(event(ResourceKind.PIECE, LifecycleAction.CREATED));
        verify(indexer).index(42);
    }
}
