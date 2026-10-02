package org.ipro.form.link;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * E3.0/E3.2.1: грамматика адреса типа Explorer. Тест фиксирует не «умеет разбирать», а решения
 * ADR-0010: ключ читается той же грамматикой, что у адреса формы, украшения адреса (регистр,
 * лишний сегмент, query, фрагмент, percent-encoding) не нормализуются — иначе у карточки
 * появился бы второй адрес, — а отказ наружу выходит без причины, чтобы host не получил
 * источник, различающий состояние ключа. Единственное переносимое украшение — инфраструктурный
 * {@code ?continue}: без него ссылка, открытая до входа в систему, после входа не открывает
 * карточку.
 *
 * <p>Якорь (<code>?view=&lt;tab&gt;[/&lt;section&gt;]</code>, E3.2.1 §8.1) — второй вопрос того же
 * адреса: {@code keyOf} отвечает про ключ, {@code anchorOf} — про форму якоря. Решает ли якорь
 * «существует ли такое место» — не грамматика, а словарь карточки приложения: неизвестный якорь
 * даёт тот же отказ, что неизвестный ключ (ADR-0010 §10), и проверяется вместе со словарём
 * ({@code org.ip.views.admin.CardAnchorTest}), а не здесь.</p>
 */
class EntityExplorerAddressTest {

    @Test
    void readsTheKeyFromTheCanonicalAddress() {
        assertThat(EntityExplorerAddress.keyOf("/entity-explorer/nomenclature"))
            .contains("nomenclature");
        assertThat(EntityExplorerAddress.keyOf("/entity-explorer/skl-nom-opa"))
            .contains("skl-nom-opa");
        assertThat(EntityExplorerAddress.format("nomenclature"))
            .isEqualTo("/entity-explorer/nomenclature");
        assertThat(EntityExplorerAddress.anchorOf("/entity-explorer/nomenclature"))
            .as("адрес без якоря якоря не выдумывает: карточка открывается как раньше")
            .isEmpty();
    }

    @Test
    void formatThenKeyOfReturnsTheSameKey() {
        for (String key : List.of("nomenclature", "prd-spec", "skl-nom-opa", "x")) {
            assertThat(EntityExplorerAddress.keyOf(EntityExplorerAddress.format(key)))
                .as("format → keyOf обязан возвращать тот же ключ: %s", key)
                .contains(key);
        }
        for (String key : List.of("nomenclature", "prd-spec")) {
            for (String anchor : List.of("access", "access/rules", "fields/table-sections")) {
                String address = EntityExplorerAddress.format(key, anchor);
                assertThat(EntityExplorerAddress.keyOf(address))
                    .as("format с якорем → keyOf обязан возвращать тот же ключ: %s", address)
                    .contains(key);
                assertThat(EntityExplorerAddress.anchorOf(address))
                    .as("format с якорем → anchorOf обязан возвращать тот же якорь: %s", address)
                    .contains(anchor);
            }
        }
    }

    /** Строки §2.2 «вкладка» и «вкладка + секция»: якорь — отдельная величина, не часть ключа. */
    @Test
    void readsTheAnchorFromTheViewParameter() {
        assertThat(EntityExplorerAddress.keyOf("/entity-explorer/nomenclature?view=access"))
            .contains("nomenclature");
        assertThat(EntityExplorerAddress.anchorOf("/entity-explorer/nomenclature?view=access"))
            .contains("access");
        assertThat(EntityExplorerAddress.anchorOf("/entity-explorer/nomenclature?view=access/rules"))
            .contains("access/rules");
        assertThat(EntityExplorerAddress.keyOf(
                "/entity-explorer/nomenclature?view=fields/table-sections"))
            .as("якорь не попадает в ключ: ключ остаётся вторым сегментом пути")
            .contains("nomenclature");
        assertThat(EntityExplorerAddress.format("nomenclature", "access"))
            .isEqualTo("/entity-explorer/nomenclature?view=access");
        assertThat(EntityExplorerAddress.format("nomenclature", "access/rules"))
            .isEqualTo("/entity-explorer/nomenclature?view=access/rules");
    }

    /**
     * Строка §2.2 «{@code ?view=nope}» целиком состоит из двух половин, и здесь — первая:
     * «неизвестный якорь» — свойство словаря карточки, а не грамматики, поэтому формально
     * корректный якорь адрес не отвергает, и место у него спрашивает host по словарю
     * (ADR-0010 §10). Отказ на неизвестное место — та же половина правила, что отказ на
     * неизвестный ключ, и проверяется вместе со словарём ({@code CardAnchorTest}).
     */
    @Test
    void theGrammarKeepsTheAnchorWithoutDecidingWhetherThePlaceExists() {
        assertThat(EntityExplorerAddress.keyOf("/entity-explorer/nomenclature?view=nope"))
            .contains("nomenclature");
        assertThat(EntityExplorerAddress.anchorOf("/entity-explorer/nomenclature?view=nope"))
            .contains("nope");
    }

    /**
     * Строка §2.2 «{@code ?view=access&view=links}»: два состояния в одном адресе — отказ, а не
     * «побеждает последнее». Вместе с якорем теряется и ключ: адрес — одна величина, и половины
     * разобранного адреса не бывает.
     */
    @Test
    void refusesAnAnchorOutsideTheGrammarOrNamedTwice() {
        for (String address : List.of(
                "/entity-explorer/nomenclature?view=access&view=links",
                "/entity-explorer/nomenclature?view=access&view=access",
                "/entity-explorer/nomenclature?view=access&continue&view=links",
                "/entity-explorer/nomenclature?view=",
                "/entity-explorer/nomenclature?view",
                "/entity-explorer/nomenclature?view=Access",
                "/entity-explorer/nomenclature?view=access/",
                "/entity-explorer/nomenclature?view=/access",
                "/entity-explorer/nomenclature?view=access/rules/x",
                "/entity-explorer/nomenclature?view=access%2Frules",
                "/entity-explorer/nomenclature?view=access-rules/",
                "/entity-explorer/nomenclature?view=access#attributes")) {
            assertThat(EntityExplorerAddress.keyOf(address))
                .as("якорь вне грамматики или названный дважды — отказ целого адреса: '%s'", address)
                .isEmpty();
            assertThat(EntityExplorerAddress.anchorOf(address))
                .as("вместе с ключом теряется и якорь: '%s'", address)
                .isEmpty();
        }
    }

    /**
     * Вход в систему приземляет на запрошенный адрес с инфраструктурным {@code ?continue}: тем же
     * правилом, что у адреса формы, параметр переносится и ключа не касается — иначе ссылка на
     * тип, открытая до входа, после входа отвечала бы отказом. Строка §2.2
     * «{@code ?view=access/rules&continue}»: перенос параметра входа якорь не снимает.
     */
    @Test
    void carriesTheInfrastructureLoginParameter() {
        assertThat(EntityExplorerAddress.keyOf("/entity-explorer/nomenclature?continue"))
            .contains("nomenclature");
        assertThat(EntityExplorerAddress.keyOf("/entity-explorer/nomenclature?continue=/records/x"))
            .as("значение переносимого параметра не разбирается: адрес типа не несёт query")
            .contains("nomenclature");
        assertThat(EntityExplorerAddress.keyOf("/entity-explorer/skl-nom-opa?continue&continue"))
            .contains("skl-nom-opa");
        assertThat(EntityExplorerAddress.anchorOf(
                "/entity-explorer/nomenclature?view=access/rules&continue"))
            .contains("access/rules");
        assertThat(EntityExplorerAddress.anchorOf(
                "/entity-explorer/nomenclature?continue&view=access"))
            .as("порядок параметров в адресе не значим: переносимый не обязан идти первым")
            .contains("access");
        assertThat(EntityExplorerAddress.keyOf(
                "/entity-explorer/nomenclature?view=access&continue=/records/x"))
            .contains("nomenclature");
    }

    /**
     * Имя параметра одно — {@code view} (§2.2), и это не «терпимость к одному параметру»: у
     * вкладки карточки не должно быть второго написания, иначе один адрес места стал бы двумя.
     * Осознанно переписанные строки: {@code ?tab=data} и {@code ?continue#attributes} отвергались и
     * до якорей, но только как «незнакомый query»; теперь это строки контракта — «имя параметра
     * одно» и «фрагмент не часть адреса».
     */
    @Test
    void theOnlyParameterNameIsView() {
        for (String address : List.of(
                "/entity-explorer/nomenclature?tab=access",
                "/entity-explorer/nomenclature?tab=data",
                "/entity-explorer/nomenclature?variant=x",
                "/entity-explorer/nomenclature?view=access&tab=data",
                "/entity-explorer/nomenclature?tab=data&view=access",
                "/entity-explorer/nomenclature?",
                "/entity-explorer/nomenclature?=view",
                "/entity-explorer/nomenclature?continue#attributes")) {
            assertThat(EntityExplorerAddress.keyOf(address))
                .as("параметр с другим именем — отказ адреса: '%s'", address)
                .isEmpty();
            assertThat(EntityExplorerAddress.anchorOf(address))
                .as("вместе с ключом теряется и якорь: '%s'", address)
                .isEmpty();
        }
    }

    @Test
    void rejectsAddressesOutsideTheCanonicalForm() {
        for (String address : List.of(
                "",
                " ",
                "/",
                "entity-explorer",
                "/entity-explorer",
                "/entity-explorer/",
                "/entity-explorer/a/b",
                "/entity-explorer/nomenclature/",
                "/entity-explorer/Nomenclature",
                "/entity-explorer/nomencl%61ture",
                "/entity-explorer/nomenclature?tab=data",
                "/entity-explorer/nomenclature#attributes",
                "/entity-explorer/nomenclature?view=access#attributes",
                "/entity-explorers/nomenclature",
                "/records/entity-explorer/42",
                "/lists/entity-explorer")) {
            assertThat(EntityExplorerAddress.keyOf(address))
                .as("адрес вне канонической формы не даёт ключа: '%s'", address)
                .isEmpty();
            assertThat(EntityExplorerAddress.anchorOf(address))
                .as("и якоря такой адрес не даёт: '%s'", address)
                .isEmpty();
        }
        assertThat(EntityExplorerAddress.keyOf(null))
            .as("отсутствие адреса — тот же отказ, что и адрес вне грамматики")
            .isEmpty();
        assertThat(EntityExplorerAddress.anchorOf(null)).isEmpty();
    }

    /**
     * «Домой» ведёт адрес, который ничего не просит. Адрес раздела Explorer заявлен даже без
     * ключа, с якорем и с отвергнутым параметром: host обязан ответить отказом, а не показать
     * главную.
     */
    @Test
    void claimsExactlyTheExplorerRoot() {
        assertThat(EntityExplorerAddress.claims("/entity-explorer/nomenclature")).isTrue();
        assertThat(EntityExplorerAddress.claims("entity-explorer/nomenclature"))
            .as("ведущий слэш необязателен: адрес окна приходит и без него")
            .isTrue();
        assertThat(EntityExplorerAddress.claims("/entity-explorer")).isTrue();
        assertThat(EntityExplorerAddress.claims("/entity-explorer/a/b")).isTrue();
        assertThat(EntityExplorerAddress.claims("/entity-explorer?view=access"))
            .as("query не меняет раздел: ключа у такого адреса нет, но главную он не показывает")
            .isTrue();
        assertThat(EntityExplorerAddress.claims("/entity-explorer?tab=data"))
            .as("и с отвергнутым параметром это всё ещё адрес раздела, а не повод показать главную")
            .isTrue();
        assertThat(EntityExplorerAddress.claims("/entity-explorer#data")).isTrue();
        assertThat(EntityExplorerAddress.claims("/entity-explorers/nomenclature")).isFalse();
        assertThat(EntityExplorerAddress.claims("/records/entity-explorer/42")).isFalse();
        assertThat(EntityExplorerAddress.claims("/lists/entity-explorer")).isFalse();
        assertThat(EntityExplorerAddress.claims("/")).isFalse();
        assertThat(EntityExplorerAddress.claims(null)).isFalse();
    }

    @Test
    void theKeyGrammarIsTheSameAsForTheFormAddress() {
        for (String key : List.of("prd_spec", "1x", "x-", "-x", "X", "x y", "x/y")) {
            assertThat(EntityExplorerAddress.keyOf("/entity-explorer/" + key))
                .as("грамматика ключа не расширяется относительно FormRoute.isKey: '%s'", key)
                .isEmpty();
            assertThatIllegalArgumentException()
                .as("format применяется к ключу каталога и на выходе грамматики падает: '%s'", key)
                .isThrownBy(() -> EntityExplorerAddress.format(key));
        }
        for (String anchor : List.of("prd_spec", "1x", "x-", "-x", "X", "x y", "x//y", "x/")) {
            assertThat(EntityExplorerAddress.anchorOf("/entity-explorer/nomenclature?view=" + anchor))
                .as("грамматика якоря — та же грамматика ключа на каждой части: '%s'", anchor)
                .isEmpty();
            assertThatIllegalArgumentException()
                .as("format применяется к якорю словаря и на выходе грамматики падает: '%s'", anchor)
                .isThrownBy(() -> EntityExplorerAddress.format("nomenclature", anchor));
        }
    }

    /**
     * Резерв занятых первых сегментов — политика публикации (список {@code reserved} в baseline
     * маршрутов), а не грамматика адреса: технически ключ стоит вторым сегментом, поэтому
     * синтаксически допустим любой ключ грамматики, включая совпадающий с разделом.
     */
    @Test
    void aKeyEqualToTheRootSegmentIsGrammaticallyValid() {
        assertThat(EntityExplorerAddress.keyOf("/entity-explorer/entity-explorer"))
            .contains("entity-explorer");
    }

    @Test
    void formatRejectsKeysAndAnchorsOutsideTheGrammar() {
        assertThatIllegalArgumentException().isThrownBy(
            () -> EntityExplorerAddress.format(""));
        assertThatIllegalArgumentException()
            .as("пустой якорь — не «якоря нет»: отсутствие выражается null, а не пустой строкой")
            .isThrownBy(() -> EntityExplorerAddress.format("nomenclature", ""));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> EntityExplorerAddress.format("nomenclature", "access/rules/x"));
        assertThatNullPointerException()
            .as("null — ошибка вызова, а не ключ вне грамматики: как у FormRoute")
            .isThrownBy(() -> EntityExplorerAddress.format(null));
        assertThat(EntityExplorerAddress.format("nomenclature", null))
            .as("«якоря нет» — это тот же адрес, что format(key): второй формы не появляется")
            .isEqualTo(EntityExplorerAddress.format("nomenclature"));
    }
}
