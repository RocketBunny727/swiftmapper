package com.rocketbunny.swiftmapper.annotations.relationship;

import java.lang.reflect.Field;

public record RelationshipField(Field field, RelationshipType type) {}