package org.ip.unidr.spike;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Properties;
import java.util.UUID;

/** Disposable schema for the explicitly requested local PostgreSQL U0 run. Never logs credentials. */
final class UniDrPostgresSandbox implements AutoCloseable {
    private final String baseUrl;
    private final String user;
    private final String password;
    private final String schema="unidr_u0_"+UUID.randomUUID().toString().replace("-","");
    private boolean closed;

    private UniDrPostgresSandbox(Properties config) {
        baseUrl=config.getProperty("spring.datasource.url");
        user=config.getProperty("spring.datasource.username");
        password=config.getProperty("spring.datasource.password");
        if(baseUrl==null || !baseUrl.matches("jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1):[0-9]+/[^?]+"))
            throw new IllegalArgumentException("U0 requires a local PostgreSQL URL without query options");
    }

    static UniDrPostgresSandbox create() throws Exception {
        var config=new Properties();
        try(var reader=Files.newBufferedReader(Path.of(System.getProperty("unidr.u0.config",
                "config/application-local.properties")),StandardCharsets.UTF_8)) { config.load(reader); }
        var sandbox=new UniDrPostgresSandbox(config);
        try(var connection=DriverManager.getConnection(sandbox.baseUrl,sandbox.user,sandbox.password);
            var statement=connection.createStatement()) {
            statement.execute("create schema "+sandbox.schema);
            Files.createDirectories(Path.of("outputs/unidr-u0"));
            Files.writeString(Path.of("outputs/unidr-u0/postgresql-environment.txt"),
                connection.getMetaData().getDatabaseProductName()+" "+connection.getMetaData().getDatabaseProductVersion()
                +"\nDisposable schema: "+sandbox.schema+"\n");
        }
        Runtime.getRuntime().addShutdownHook(new Thread(()-> {
            try {sandbox.close();} catch(Exception ignored) {System.err.println("U0 sandbox cleanup failed: "+sandbox.schema);}
        },"unidr-u0-postgresql-cleanup"));
        return sandbox;
    }

    String url() {return baseUrl+"?currentSchema="+schema;}
    String user() {return user;}
    String password() {return password;}
    String schema() {return schema;}

    @Override public synchronized void close() throws Exception {
        if(closed) return;
        if(!schema.matches("unidr_u0_[0-9a-f]{32}")) throw new IllegalStateException("Invalid scratch schema");
        try(var connection=DriverManager.getConnection(baseUrl,user,password);var statement=connection.createStatement()) {
            statement.execute("drop schema if exists "+schema+" cascade");
        }
        Files.writeString(Path.of("outputs/unidr-u0/postgresql-cleanup.txt"),"Dropped scratch schema: "+schema+"\n");
        closed=true;
    }
}
