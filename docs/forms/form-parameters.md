# Параметры открытия карточки

Форма на `@FormFor` объявляет бизнес-параметры с типами. Параметры управляют
поведением; состав полей задаётся `layout()`/`adjust()` и именованным вариантом.

```java
public static final FormParameter<List<Class<?>>> READ_ONLY_SECTIONS =
    FormParameter.sections("readOnlySections", PrdSpecMtr.class, PrdSpecOper.class);

@Override protected void parameters(FormParameters p) { p.add(READ_ONLY_SECTIONS); }

@Override protected void behavior(FormBehavior b) {
    b.setReadOnlySections(b.parameter(READ_ONLY_SECTIONS));
}
```

Вызов навигатора передаёт типизированные значения через существующий канал Map:

```java
navigator.openItemForm(PrdSpec.class, "full", id, onSaved,
    FormParameters.values(PrdSpecItemForm.READ_ONLY_SECTIONS.with(List.of(PrdSpecOper.class))));
```

`FormParameter.of(name, type, defaultValue)` объявляет скалярный параметр.
`sections(name, allowed...)` объявляет список разрешённых классов секций с пустым
списком по умолчанию. Список копируется; настройки одного открытия не переходят
в другое. Отсутствующее или null-значение означает значение по умолчанию.

Фабрика `@FormFor` проверяет имена, тип контейнера и каждый элемент списка до
создания редакторов и подключения секций. Неизвестный бизнес-параметр, повторное
объявление или чтение необъявленного ключа — ошибка. При стартовой проверке
`behavior()` получает значения по умолчанию без создания редакторов.

Прежний `Map.of("readOnlySections", List.of(...))` остаётся входом совместимости
и проходит те же проверки. Данные координатора `presetEntity`, `presetRows`,
`initialValues`, `contextFilters`, `readOnly`, `fields`, `seedFilter`, `seedFilters`
не являются бизнес-параметрами; эти имена зарезервированы. Фильтры открытия
по-прежнему передаются контрактом `Filter`, отдельно от параметров поведения.
Старые фабрики с `FormContext.getParameter` сохраняют прежний режим совместимости.
