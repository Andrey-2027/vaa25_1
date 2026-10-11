package org.ipro.form.link;

import org.ipro.metadata.GridViewState;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ListViewStateTokenTest {
    @Test void listRouteRoundTripKeepsStateAndOldRoutes() {
        String json = new GridViewState(List.of(), List.of()).withOptions(Map.of(
            "register.mode", "Срез последних", "register.asOf", "2026-10-09T12:35:16.123")).toJson();
        var codec = new FormRouteCodec();
        var route = new FormRoute(FormRouteKind.LIST, "prices", null, "compact", ListViewStateToken.encode(json));
        var parsed = codec.parse(codec.format(route)).parsedRoute().orElseThrow();
        assertThat(parsed).isEqualTo(route);
        assertThat(GridViewState.fromJson(ListViewStateToken.decode(parsed.state())).options())
            .containsEntry("register.mode", "Срез последних");
        assertThat(codec.parse("/lists/prices").parsedRoute().orElseThrow().state()).isNull();
    }
    @Test void malformedRepeatedOrRecordStateFailsClosed() {
        var codec = new FormRouteCodec();
        String token = ListViewStateToken.encode(new GridViewState(List.of(), List.of()).toJson());
        assertThat(codec.parse("/lists/prices?state=%%% ").parsedRoute()).isEmpty();
        assertThat(codec.parse("/lists/prices?state=" + token + "&state=" + token).parsedRoute()).isEmpty();
        assertThat(codec.parse("/records/prices/1?state=" + token).parsedRoute()).isEmpty();
        assertThatThrownBy(() -> ListViewStateToken.decode("A".repeat(32769))).isInstanceOf(IllegalArgumentException.class);
    }
}
