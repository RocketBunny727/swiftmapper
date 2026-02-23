package com.rocketbunny.swiftmapper.utils.naming;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class NamingStrategyTest {

    @Test
    public void testGetTableName() {
        assertEquals("users", NamingStrategy.getTableName(User.class));
        assertEquals("user_profiles", NamingStrategy.getTableName(UserProfile.class));
        assertEquals("http_requests", NamingStrategy.getTableName(HTTPRequest.class));
    }

    static class User {}
    static class UserProfile {}
    static class HTTPRequest {}
}
