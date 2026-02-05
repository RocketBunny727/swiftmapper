Найденные проблемы:
1. Session.java - save() возвращает T, но метод объявлен как public T save(Object entity) - несоответствие generic типов
2. ConnectionManager.java - в createIdColumnDefinition нет обработки для SEQUENCE стратегии
3. EntityMapper.java - нет поддержки ленивой загрузки и отношений между сущностями
4. QueryMethodParser.java - очень ограничен, поддерживает только And, нет Or, Like, GreaterThan и т.д.

Уточнения:
- Какую базу данных использовать основной? (PostgreSQL, H2, или обе?)
- Нужна ли поддержка миграций/скриптов изменения схемы?
- Хотите ли поддержку SQL-конструктора (Criteria API) или только method name parsing?

TODO:
1. Аннотации для отношений (Relationships)
- Lazy loading прокси (требует CGLIB)
- Каскадные операции (сейчас только аннотации)
- ManyToMany inverse side

2. Улучшенный QueryMethodParser ✅
- findByNameLike → LIKE %?%
- findByAgeGreaterThan → > ?
- findByStatusIn → IN (?)
- findByNameOrEmail → OR
- findByCreatedAtBetween → BETWEEN ? AND ?

3. Lazy Loading для отношений 

    Прокси-классы для отложенной загрузки связанных сущностей.

4. PreparedStatement кэширование

    Для производительности как в Dapper.