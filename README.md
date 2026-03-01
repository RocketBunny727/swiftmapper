Уточнения:
- Какую базу данных использовать основной? (PostgreSQL, H2, или обе?) - ОБЕ
- Нужна ли поддержка миграций/скриптов изменения схемы? - НУЖНА
- Хотите ли поддержку SQL-конструктора (Criteria API) или только method name parsing? - НУЖЕН КОНСТРУКТОР

TODO:
1. Аннотации для отношений (Relationships)
- Lazy loading прокси (требует CGLIB) - ЕСТЬ НА bytebunny
- Каскадные операции (сейчас только аннотации)
- ManyToMany inverse side

2. Улучшенный QueryMethodParser
- findByNameLike → LIKE %?%
- findByAgeGreaterThan → > ?
- findByStatusIn → IN (?)
- findByNameOrEmail → OR
- findByCreatedAtBetween → BETWEEN ? AND ?

3. Lazy Loading для отношений 

    Прокси-классы для отложенной загрузки связанных сущностей.

4. PreparedStatement кэширование

    Для производительности как в Dapper.

5. Журнал всех операций и транзакций

6. Возможность ставить на данные валидацию (@Valid)

7. Конструктор SQL запросов для разработчика

ПРОБЛЕМЫ:

