package ir.vigo.notification.inapp.rest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TrustedJwksTests {
    @Test void boundsTransportAndDisablesRedirects() throws Exception {
        var connection = mock(HttpURLConnection.class);
        when(connection.getResponseCode()).thenReturn(200);
        when(connection.getInputStream()).thenReturn(new ByteArrayInputStream("{\"keys\":[]}".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(TrustedJwks.download(connection)).isEqualTo("{\"keys\":[]}");
        verify(connection).setInstanceFollowRedirects(false);
        verify(connection).setConnectTimeout(2000);
        verify(connection).setReadTimeout(2000);
        verify(connection).disconnect();
    }

    @ParameterizedTest @ValueSource(ints = {301, 302, 404, 500, 503})
    void rejectsNonSuccessIncludingRedirect(int status) throws Exception {
        var connection = mock(HttpURLConnection.class);
        when(connection.getResponseCode()).thenReturn(status);
        assertThatThrownBy(() -> TrustedJwks.download(connection)).isInstanceOf(IOException.class);
        verify(connection, never()).getInputStream();
        verify(connection).disconnect();
    }

    @Test void rejectsOversizedResponse() throws Exception {
        var connection = mock(HttpURLConnection.class);
        when(connection.getResponseCode()).thenReturn(200);
        when(connection.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[65537]));
        assertThatThrownBy(() -> TrustedJwks.download(connection)).isInstanceOf(IOException.class);
        verify(connection).disconnect();
    }

    @Test void disconnectsOnNetworkFailure() throws Exception {
        var connection = mock(HttpURLConnection.class);
        when(connection.getResponseCode()).thenThrow(new java.net.SocketTimeoutException());
        assertThatThrownBy(() -> TrustedJwks.download(connection)).isInstanceOf(IOException.class);
        verify(connection).disconnect();
    }
}
