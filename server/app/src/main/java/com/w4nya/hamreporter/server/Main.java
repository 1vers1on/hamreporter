package com.w4nya.hamreporter.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        try {
            App app = App.getInstance();
            app.run(args);
        } catch (Exception e) {
            logger.error("Unhandled exception in main", e);
            System.exit(1);
        }
    }
}
