package com.vendoryx.tools;

import com.vendoryx.util.PasswordUtil;

/**
 * CLI: generate BCrypt hash for admin_users.password_hash.
 *
 * <pre>
 * java -cp "target/classes;target/dependency/*" com.vendoryx.tools.GenBcrypt 'YourPassword'
 * </pre>
 *
 * Then SQL:
 * <pre>
 * UPDATE admin_users SET password_hash = '&lt;hash&gt;', updated_at = now() WHERE username = 'owner';
 * </pre>
 */
public final class GenBcrypt {

    private GenBcrypt() {}

    public static void main(String[] args) {
        if (args.length < 1 || args[0].isBlank()) {
            System.err.println("Usage: GenBcrypt <plain-password>");
            System.exit(1);
        }
        String hash = PasswordUtil.hash(args[0]);
        System.out.println(hash);
        System.out.println();
        System.out.println("-- SQL example:");
        System.out.println("UPDATE admin_users");
        System.out.println("SET password_hash = '" + hash.replace("'", "''") + "',");
        System.out.println("    updated_at = now()");
        System.out.println("WHERE username = 'owner';");
    }
}
