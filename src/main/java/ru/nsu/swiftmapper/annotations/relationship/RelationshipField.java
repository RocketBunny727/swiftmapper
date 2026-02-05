package ru.nsu.swiftmapper.annotations.relationship;

import java.lang.reflect.Field;

public record RelationshipField(Field field, RelationshipType type) {}
