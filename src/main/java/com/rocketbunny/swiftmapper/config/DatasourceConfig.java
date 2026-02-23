package com.rocketbunny.swiftmapper.config;

import lombok.Data;

@Data
public class DatasourceConfig {
    public final String url, username, password, driverClassName;

    public DatasourceConfig(String url, String username, String password, String driverClassName) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.driverClassName = driverClassName;
    }
}
