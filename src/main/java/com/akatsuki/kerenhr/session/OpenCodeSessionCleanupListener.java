package com.akatsuki.kerenhr.session;

import com.akatsuki.kerenhr.opencode.OpenCodeChatModel;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Cleans up the upstream OpenCode session when the HTTP session is destroyed.
 *
 * <p>Spring Boot automatically registers {@code @Component} beans that implement
 * {@link HttpSessionListener} with the embedded Tomcat servlet container, so no
 * additional configuration is required.
 *
 * <p>This ensures that when a browser session expires (default: 30 minutes of inactivity)
 * or is explicitly invalidated, the corresponding OpenCode session is also deleted and
 * does not leak resources on the OpenCode gateway.
 */
@Slf4j
@Component
public class OpenCodeSessionCleanupListener implements HttpSessionListener {

    private final OpenCodeChatModel openCodeChatModel;

    public OpenCodeSessionCleanupListener(OpenCodeChatModel openCodeChatModel) {
        this.openCodeChatModel = openCodeChatModel;
    }

    @Override
    public void sessionCreated(HttpSessionEvent event) {
        log.debug("HTTP session created httpSession={}", event.getSession().getId());
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        String httpSessionId = event.getSession().getId();
        String openCodeSessionId = (String) event.getSession().getAttribute(SessionConstants.OPENCODE_SESSION_ATTR);

        if (openCodeSessionId != null) {
            log.info("HTTP session destroyed httpSession={} — cleaning up openCodeSession='{}'",
                    httpSessionId, openCodeSessionId);
            openCodeChatModel.deleteSession(openCodeSessionId);
        } else {
            // Session existed but never had a chat (e.g. health-check, static asset request)
            log.debug("HTTP session destroyed httpSession={} — no OpenCode session to clean up", httpSessionId);
        }
    }
}
