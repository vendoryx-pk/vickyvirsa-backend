package com.vendoryx;

import com.vendoryx.config.EnvConfig;
import org.apache.catalina.Context;
import org.apache.catalina.WebResourceRoot;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.catalina.webresources.DirResourceSet;
import org.apache.catalina.webresources.StandardRoot;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URL;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.stream.Stream;

public class AppServer {

    public static void main(String[] args) throws Exception {
        EnvConfig.validateRequired();

        int port = resolvePort();
        Tomcat tomcat = new Tomcat();
        tomcat.setPort(port);
        tomcat.setHostname("0.0.0.0");

        File tomcatBase = resolveTomcatBaseDir();
        if (!tomcatBase.exists() && !tomcatBase.mkdirs()) {
            throw new IllegalStateException("Cannot create Tomcat base dir: " + tomcatBase.getAbsolutePath());
        }
        tomcat.setBaseDir(tomcatBase.getAbsolutePath());

        Connector connector = tomcat.getConnector();
        connector.setProperty("address", "0.0.0.0");

        File webappDir = resolveWebappDir();
        File classesDir = resolveClassesDir();

        if (!webappDir.isDirectory()) {
            throw new IllegalStateException("Webapp directory not found: " + webappDir.getAbsolutePath());
        }

        Context context = tomcat.addWebapp("", webappDir.getAbsolutePath());

        if (classesDir != null && classesDir.isDirectory()) {
            WebResourceRoot resources = new StandardRoot(context);
            resources.addPreResources(new DirResourceSet(
                    resources,
                    "/WEB-INF/classes",
                    classesDir.getAbsolutePath(),
                    "/"
            ));
            context.setResources(resources);
        }

        tomcat.start();
        System.out.println("========================================");
        System.out.println(" VickyVirsa backend is running");
        System.out.println(" http://0.0.0.0:" + port);
        System.out.println(" GET  /health  and  GET /api/health");
        System.out.println(" Public: GET /api/products, POST /api/orders/guest");
        System.out.println(" Admin: /api/admin/* (JWT OWNER|STAFF)");
        System.out.println("========================================");
        tomcat.getServer().await();
    }

    /** Prefer {@code System.getenv("PORT")}, then EnvConfig (env / .env), else 8080. */
    private static int resolvePort() {
        String raw = System.getenv("PORT");
        if (raw != null && !raw.isBlank()) {
            try {
                int port = Integer.parseInt(raw.trim());
                if (port >= 1 && port <= 65535) {
                    return port;
                }
                throw new IllegalStateException("PORT must be between 1 and 65535 (got " + port + ").");
            } catch (NumberFormatException e) {
                throw new IllegalStateException("PORT must be a number (got '" + raw + "').");
            }
        }
        return EnvConfig.port();
    }

    private static File resolveTomcatBaseDir() {
        String fromEnv = System.getenv("VENDORYX_TOMCAT_BASE");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return new File(fromEnv.trim());
        }
        File hfApp = new File("/home/user/app");
        if (hfApp.isDirectory()) {
            return new File(hfApp, "tomcat-base");
        }
        String tmp = System.getProperty("java.io.tmpdir");
        return new File(tmp, "vickyvirsa-tomcat-base");
    }

    private static File resolveWebappDir() throws IOException {
        String fromEnv = System.getenv("VENDORYX_WEBAPP_DIR");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return new File(fromEnv.trim());
        }
        File hf = new File("/home/user/app/webapp");
        if (hf.isDirectory()) {
            return hf;
        }
        File container = new File("/app/webapp");
        if (container.isDirectory()) {
            return container;
        }
        File local = new File("src/main/webapp");
        if (local.isDirectory()) {
            return local;
        }
        // Fat JAR: extract classpath:/webapp to a temp directory
        return extractClasspathWebapp();
    }

    private static File extractClasspathWebapp() throws IOException {
        URL marker = AppServer.class.getClassLoader().getResource("webapp/WEB-INF/web.xml");
        if (marker == null) {
            throw new IllegalStateException(
                    "webapp not found on disk or classpath (expected webapp/WEB-INF/web.xml in the JAR)");
        }

        Path dest = Files.createTempDirectory("vickyvirsa-webapp-");
        dest.toFile().deleteOnExit();

        try {
            String protocol = marker.getProtocol();
            if ("file".equals(protocol)) {
                Path webappRoot = Path.of(marker.toURI()).getParent().getParent();
                copyTree(webappRoot, dest);
            } else if ("jar".equals(protocol)) {
                String spec = marker.toExternalForm();
                int bang = spec.indexOf("!/");
                URI jarUri = URI.create(spec.substring(0, bang));
                try (FileSystem fs = FileSystems.newFileSystem(jarUri, Collections.emptyMap())) {
                    Path webappRoot = fs.getPath("/webapp");
                    copyTree(webappRoot, dest);
                }
            } else {
                throw new IllegalStateException("Unsupported webapp resource protocol: " + protocol);
            }
        } catch (java.net.URISyntaxException e) {
            throw new IOException("Invalid webapp resource URI: " + marker, e);
        }
        System.out.println("Extracted classpath webapp to " + dest.toAbsolutePath());
        return dest.toFile();
    }

    private static void copyTree(Path source, Path dest) throws IOException {
        try (Stream<Path> walk = Files.walk(source)) {
            walk.forEach(src -> {
                try {
                    Path relative = source.relativize(src);
                    Path target = dest.resolve(relative.toString());
                    if (Files.isDirectory(src)) {
                        Files.createDirectories(target);
                    } else {
                        Files.createDirectories(target.getParent());
                        try (InputStream in = Files.newInputStream(src);
                             OutputStream out = Files.newOutputStream(target)) {
                            in.transferTo(out);
                        }
                        target.toFile().deleteOnExit();
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Failed copying webapp resource: " + src, e);
                }
            });
        }
    }

    /**
     * When running from an exploded {@code target/classes} layout, mount it under WEB-INF/classes.
     * Fat JAR already has classes on the classpath — return null so we do not double-mount.
     */
    private static File resolveClassesDir() {
        String fromEnv = System.getenv("VENDORYX_CLASSES_DIR");
        if (fromEnv != null && !fromEnv.isBlank()) {
            return new File(fromEnv.trim());
        }
        if (runningFromFatJar()) {
            return null;
        }
        File hf = new File("/home/user/app/classes");
        if (hf.isDirectory()) {
            return hf;
        }
        File container = new File("/app/classes");
        if (container.isDirectory()) {
            return container;
        }
        File local = new File("target/classes");
        return local.isDirectory() ? local : null;
    }

    private static boolean runningFromFatJar() {
        try {
            URL loc = AppServer.class.getProtectionDomain().getCodeSource().getLocation();
            if (loc == null) {
                return false;
            }
            String path = loc.getPath();
            return path != null && path.endsWith(".jar");
        } catch (Exception e) {
            return false;
        }
    }
}
