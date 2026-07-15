package dev.qcoding.businesscopilot;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Optional dedicated, read-only Data Copilot query connection. */
@ConfigurationProperties(prefix = "business-copilot.data-copilot.datasource")
public class BusinessQueryDataSourceProperties {

    private boolean enabled;
    private String url;
    private String username;
    private String password;
    private String driverClassName = "org.postgresql.Driver";
    private int maximumPoolSize = 5;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDriverClassName() {
        return driverClassName;
    }

    public void setDriverClassName(String driverClassName) {
        this.driverClassName = driverClassName;
    }

    public int getMaximumPoolSize() {
        return maximumPoolSize;
    }

    public void setMaximumPoolSize(int maximumPoolSize) {
        this.maximumPoolSize = maximumPoolSize;
    }
}
