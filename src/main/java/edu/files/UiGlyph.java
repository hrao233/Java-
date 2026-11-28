package edu.files;

import javafx.scene.layout.Pane;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Scale;

enum UiGlyph {
    BACK("M19 12H5 M11 6L5 12l6 6"),
    FORWARD("M5 12h14 M13 6l6 6-6 6"),
    UP("M12 20V4 M6 10l6-6 6 6"),
    REFRESH("M20 10a8 8 0 1 0 0 5 M20 4v6h-6"),
    SEARCH("M16 10a6 6 0 1 1-12 0 6 6 0 1 1 12 0 Z M14.5 14.5L20 20"),
    ADD("M12 4v16 M4 12h16"),
    CUT("M8 7a3 3 0 1 1-6 0 3 3 0 1 1 6 0 Z M8 17a3 3 0 1 1-6 0 3 3 0 1 1 6 0 Z "
            + "M7 9l13 10 M7 15L20 5"),
    COPY("M9 15H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v4 "
            + "M11 9h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2h-8a2 2 0 0 1-2-2v-8a2 2 0 0 1 2-2z"),
    PASTE("M9 5H6a2 2 0 0 0-2 2v12a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7a2 2 0 0 0-2-2h-3 "
            + "M9 3h6v5H9z M8 12h8 M8 16h5"),
    RENAME("M6 7V5h12v2 M12 5v14 M8 19h8"),
    DELETE("M4 6h16 M9 6V3h6v3 M6 6l1 14h10l1-14 M10 10v6 M14 10v6"),
    SORT("M7 5v14 M3 9l4-4 4 4 M17 19V5 M13 15l4 4 4-4"),
    VIEW("M4 6h16 M4 12h16 M4 18h16"),
    MORE("M5.5 12h.1 M12 12h.1 M18.5 12h.1"),
    DETAILS("M4 5h16v14H4z M14 5v14 M7 10h4 M7 14h4"),
    SPLIT("M4 5h16v14H4z M12 5v14"),
    EDIT("M5 15L16 4l4 4L9 19l-5 1z M14 6l4 4"),
    DOWN("M6 9l6 6 6-6"),
    RIGHT("M9 6l6 6-6 6"),
    CLOSE("M6 6l12 12 M18 6L6 18"),
    GALLERY("M4 5h16v14H4z M4 15l5-5 4 4 2-2 5 5 M15 8h.1"),
    UNDO("M9 5l-5 5 5 5 M4 10h10a5 5 0 0 1 0 10h-2"),
    PIN("M10 9V5h7v4l3 5H7l3-5z M13.5 14v7"),
    UNPIN("M9 6h8v4l3 4h-4 M12 14H6l3-4 M12 14v7 M3 3l18 18"),
    CLOCK("M21 12a9 9 0 1 1-18 0 9 9 0 1 1 18 0 Z M12 7v5l3 2"),
    FAVORITE("M12 3l2.7 5.7 6.3.9-4.5 4.4 1 6.3-5.5-3-5.5 3 1-6.3L3 9.6l6.3-.9z"),
    PEOPLE("M16 8a4 4 0 1 1-8 0 4 4 0 1 1 8 0 Z M4 20v-2a4 4 0 0 1 4-4h8a4 4 0 0 1 4 4v2"),
    SETTINGS("M10 3h4l.5 2.5 2 .9 2.2-1.2 2.8 2.8-1.2 2.2.9 2 2.5.5v4l-2.5.5-.9 2 "
            + "-3 1-2-.9-.5-2.5h-4l-.5 2.5-2 .9-3-1-.9-2-2.5-.5v-4l2.5-.5.9-2-1.2-2.2 2.8-2.8 2.2 1.2z",
            "M15 12a3 3 0 1 1-6 0 3 3 0 1 1 6 0 Z"),
    PROPERTIES("M4 7h16 M4 12h16 M4 17h16 M8 5v4 M16 10v4 M11 15v4"),
    CLEAN("M7 10l7 7 M12 4l-4 8-4 3 5 5 3-4 8-4z"),
    DRIVE("M6 6h12l3 8v4H3v-4z M3 14h18 M7 16h.1"),
    FORMAT("M6 6h12l3 8v4H3v-4z M3 14h18 M7 16h.1 M11 16h.1 M15 16h.1"),
    NETWORK("M5 4h14v10H5z M12 14v4 M7 20h10 M12 18v2"),
    MEDIA("M9 5v14l11-7z"),
    DISCONNECT("M10 8l3-3a4 4 0 0 1 6 6l-3 3 M14 16l-3 3a4 4 0 0 1-6-6l3-3 M9 15l6-6 M3 3l18 18"),
    SELECT_ALL("M4 4h16v16H4z M8 12l3 3 5-6"),
    SELECT_NONE("M4 4h16v16H4z"),
    INVERT("M4 4h16v16H4z M4 20L20 4 M4 15L15 4 M9 20L20 9");

    private static final double VIEW_BOX_SIZE = 24;
    private final String[] paths;

    UiGlyph(String... paths) {
        this.paths = paths;
    }

    Pane graphic(double size) {
        Pane icon = new Pane();
        icon.setMinSize(size, size);
        icon.setPrefSize(size, size);
        icon.setMaxSize(size, size);
        icon.setMouseTransparent(true);
        double scale = size / VIEW_BOX_SIZE;
        for (String data : paths) {
            SVGPath path = new SVGPath();
            path.setContent(data);
            path.getStyleClass().add("glyph-stroke");
            path.getTransforms().add(new Scale(scale, scale));
            icon.getChildren().add(path);
        }
        return icon;
    }
}
