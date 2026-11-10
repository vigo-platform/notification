package ir.vigo.notification.inapp.rest;

import java.security.Principal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import ir.vigo.notification.inapp.InAppNotification;
import ir.vigo.notification.inapp.JdbcInAppNotifications;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class NotificationRestControllerTests {
    private static final String ROOT = "/api/v1/notification/notifications";
    private static final Instant NOW = Instant.parse("2026-11-10T08:00:00Z");
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Principal A = () -> "test-a";
    private static final Principal B = () -> "test-b";
    private JdbcInAppNotifications store;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        store = mock(JdbcInAppNotifications.class);
        // Explicit test identities; never registered in application configuration.
        VerifiedNotificationIdentity identity = principal -> {
            if (principal == A) return "test-a";
            if (principal == B) return "test-b";
            throw new VerifiedNotificationIdentity.Unauthorized();
        };
        mvc = MockMvcBuilders.standaloneSetup(new NotificationRestController(
                new NotificationRestService(store, identity), Clock.fixed(NOW, ZoneOffset.UTC))).build();
    }

    @Test
    void listUsesDefaultPageAndPublicSnakeCaseDto() throws Exception {
        when(store.list("test-a", 0, 20)).thenReturn(List.of(
                new InAppNotification(ID, "test-a", "Test title", "Test body", NOW, null)));
        mvc.perform(get(ROOT).principal(A))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.code").value("NOTIFICATIONS_LISTED"))
                .andExpect(jsonPath("$.message_fa").isString())
                .andExpect(jsonPath("$.timestamp").value(NOW.toString()))
                .andExpect(jsonPath("$.data.page").value(0)).andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.items[0].id").value(ID.toString()))
                .andExpect(jsonPath("$.data.items[0].created_at").value(NOW.toString()))
                .andExpect(jsonPath("$.data.items[0].read_at").value(nullValue()))
                .andExpect(jsonPath("$.data.items[0].owner").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].owner_id").doesNotExist());
    }

    @Test
    void paginationAndEmptyPageAreExplicit() throws Exception {
        when(store.list("test-a", 3, 5)).thenReturn(List.of());
        mvc.perform(get(ROOT).principal(A).param("page", "3").param("size", "5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.page").value(3)).andExpect(jsonPath("$.data.size").value(5));
        verify(store).list("test-a", 3, 5);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "101", "0", "abc", "2147483648", "", "1.5", " 20"})
    void rejectsInvalidSizeWithoutDatabaseAccess(String size) throws Exception {
        mvc.perform(get(ROOT).principal(A).param("size", size))
                .andExpect(status().isBadRequest()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.data[0].field").value("size"));
        verifyNoInteractions(store);
    }

    @Test
    void rejectsInvalidPageAndDuplicateParameters() throws Exception {
        mvc.perform(get(ROOT).principal(A).param("page", "-1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.data[0].field").value("page"));
        mvc.perform(get(ROOT).principal(A).param("size", "10", "20"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(store);
    }

    @Test
    void missingAuthenticationWinsOverInvalidInputForAllOperations() throws Exception {
        for (var request : List.of(get(ROOT).param("size", "bad"), get(ROOT + "/unread-count"),
                patch(ROOT + "/invalid/read"))) {
            mvc.perform(request).andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                    .andExpect(jsonPath("$.data").value(nullValue()));
        }
        verifyNoInteractions(store);
    }

    @Test
    void arbitraryPrincipalOrUnverifiedBearerHeaderCannotAuthenticate() throws Exception {
        mvc.perform(get(ROOT).principal(() -> "test-a")).andExpect(status().isUnauthorized());
        mvc.perform(get(ROOT).header("Authorization", "Bearer test-unverified-token")
                .header("X-User-Id", "test-a")).andExpect(status().isUnauthorized());
        verifyNoInteractions(store);
    }

    @Test
    void clientSuppliedOwnerFieldsNeverSelectOwnership() throws Exception {
        when(store.list("test-a", 0, 20)).thenReturn(List.of());
        mvc.perform(get(ROOT).principal(A).param("owner_id", "test-b").param("userId", "test-b")
                .header("X-User-Id", "test-b")).andExpect(status().isOk());
        mvc.perform(get(ROOT + "/unread-count").principal(A).param("owner_id", "test-b"))
                .andExpect(status().isOk());
        when(store.markRead("test-a", ID)).thenReturn(true);
        mvc.perform(patch(ROOT + "/" + ID + "/read").principal(A)
                .contentType("application/json").content("{\"owner_id\":\"test-b\"}"))
                .andExpect(status().isOk());
        verify(store).list("test-a", 0, 20);
        verify(store).unreadCount("test-a");
        verify(store).markRead("test-a", ID);
        verifyNoMoreInteractions(store);
    }

    @Test
    void unreadCountAndRepeatedMarkReadHaveStableShapes() throws Exception {
        when(store.unreadCount("test-b")).thenReturn(7L);
        mvc.perform(get(ROOT + "/unread-count").principal(B)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unread_count").value(7));
        when(store.markRead("test-a", ID)).thenReturn(true);
        for (int i = 0; i < 2; i++) {
            mvc.perform(patch(ROOT + "/" + ID + "/read").principal(A)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("NOTIFICATION_READ"))
                    .andExpect(jsonPath("$.data").value(nullValue()));
        }
    }

    @Test
    void missingAndForeignIdsHaveIdenticalResponses() throws Exception {
        var foreign = mvc.perform(patch(ROOT + "/" + ID + "/read").principal(A))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        var absent = mvc.perform(patch(ROOT + "/00000000-0000-0000-0000-000000000002/read").principal(A))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(foreign).isEqualTo(absent);
    }

    @Test
    void malformedUuidIsRejectedAfterAuthentication() throws Exception {
        mvc.perform(patch(ROOT + "/1-1-1-1-1/read").principal(A)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data[0].field").value("id"));
        verifyNoInteractions(store);
    }

    @Test
    void persistenceFailureIsSanitized() throws Exception {
        when(store.unreadCount("test-a")).thenThrow(new IllegalStateException("test-private-driver-details"));
        mvc.perform(get(ROOT + "/unread-count").principal(A)).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("test-private"))));
    }
}
