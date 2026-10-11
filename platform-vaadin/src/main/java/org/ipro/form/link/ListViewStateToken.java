package org.ipro.form.link;

import org.ipro.metadata.GridViewState;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Bounded URL-safe serialized list intent. It confers no access permissions. */
public final class ListViewStateToken {
    private ListViewStateToken() { }
    public static String encode(String json) {
        GridViewState.fromJson(json);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        decode(token);
        return token;
    }
    public static String decode(String token) {
        if (token == null || token.isEmpty() || token.length() > 32768 || !token.matches("[A-Za-z0-9_-]+"))
            throw new IllegalArgumentException("Некорректное состояние списка в ссылке");
        String json = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
        GridViewState.fromJson(json);
        return json;
    }
}
