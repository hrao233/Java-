package edu.files;

import javafx.scene.control.Label;
import javafx.scene.text.Font;

enum UiGlyph {
    ADD('\ue710', "+"), CUT('\ue8c6', "✂"), COPY('\ue8c8', "▣"), PASTE('\ue77f', "▤"),
    RENAME('\ue8ac', "Aa"), DELETE('\ue74d', "×"), SORT('\ue8cb', "↕"), VIEW('\ue8fd', "≡"),
    MORE('\ue712', "⋯"), DETAILS('\ue8a0', "◫"), EDIT('\ue70f', "✎"),
    DOWN('\ue96e', "⌄"), RIGHT('\ue970', "›"), CLOSE('\ue8bb', "×"), GALLERY('\ue8b9', "▧"),
    UNDO('\ue7a7', "↶"), PIN('\ue718', "⌖"), UNPIN('\ue77a', "⌖"), CLOCK('\ue917', "◷"),
    FAVORITE('\ue734', "☆"), PEOPLE('\ue716', "♧"), SETTINGS('\ue713', "⚙"), PROPERTIES('\ue90f', "⚒"),
    CLEAN('\ue894', "◇"), DRIVE('\ueda2', "▤"), FORMAT('\ue777', "↻"), NETWORK('\ue968', "▣"),
    MEDIA('\ue714', "▷"), DISCONNECT('\ue711', "×"), SELECT_ALL('\ue8b3', "▣"),
    SELECT_NONE('\ue739', "□"), INVERT('\ue8ab', "▧");

    private static final String FONT = Font.getFamilies().contains("Segoe Fluent Icons") ? "Segoe Fluent Icons"
            : Font.getFamilies().contains("Segoe MDL2 Assets") ? "Segoe MDL2 Assets" : null;

    private final char symbol;
    private final String alternative;

    UiGlyph(char symbol, String alternative) {
        this.symbol = symbol;
        this.alternative = alternative;
    }

    Label graphic(double size, String styleClass) {
        Label icon = new Label(FONT == null ? alternative : String.valueOf(symbol));
        icon.setFont(Font.font(FONT == null ? "System" : FONT, size));
        icon.getStyleClass().add(styleClass);
        icon.setMouseTransparent(true);
        return icon;
    }
}
