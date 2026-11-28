package edu.files;

import javafx.beans.binding.Bindings;
import javafx.scene.Scene;
import javafx.stage.PopupWindow;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.List;

enum UiTheme {
    LIGHT("theme-light.css"), DARK("theme-dark.css");

    private static final String COMPONENTS = stylesheet("ui-components.css");
    private static final String FILE_MANAGER = stylesheet("file-manager.css");
    private final String palette;

    UiTheme(String palette) {
        this.palette = stylesheet(palette);
    }

    void applyTo(Scene scene) {
        scene.getStylesheets().setAll(palette, COMPONENTS, FILE_MANAGER);
    }

    void applyTo(Stage stage) {
        applyTo(stage.getScene());
        // PopupWindow copies the owner Scene's stylesheets when shown.
        // Refresh those already visible, including the menus of owned dialogs.
        for (Window window : List.copyOf(Window.getWindows())) {
            if (window instanceof PopupWindow popup && belongsTo(popup, stage)) {
                applyTo(popup.getScene());
            }
        }
    }

    static void inherit(Scene scene, Window owner) {
        Bindings.bindContent(scene.getStylesheets(), owner.getScene().getStylesheets());
    }

    private static boolean belongsTo(Window window, Stage stage) {
        while (window != null && window != stage) {
            window = window instanceof PopupWindow popup ? popup.getOwnerWindow()
                    : window instanceof Stage child ? child.getOwner() : null;
        }
        return window == stage;
    }

    private static String stylesheet(String name) {
        return UiTheme.class.getResource(name).toExternalForm();
    }
}
