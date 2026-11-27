package edu.files;

import com.ibm.icu.text.Transliterator;
import edu.files.BrowserView.Field;

import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ColumnFilters {
    record Bucket(int order, String label) { }

    private static final long KIB = 1024;
    private static final long MIB = KIB * KIB;
    private static final long GIB = MIB * KIB;
    private final Map<Integer, Integer> initials = new HashMap<>();
    private final Map<Field, Set<Bucket>> selected = new EnumMap<>(Field.class);
    private Transliterator pinyin;

    static boolean supports(Field field) {
        return field == Field.NAME || field == Field.MODIFIED || field == Field.TYPE || field == Field.SIZE;
    }

    boolean active() { return !selected.isEmpty(); }
    boolean active(Field field) { return selected.containsKey(field); }
    boolean selected(Field field, Bucket bucket) { return selected.getOrDefault(field, Set.of()).contains(bucket); }

    void select(Field field, Bucket bucket, boolean checked) {
        if (checked) {
            selected.computeIfAbsent(field, key -> new HashSet<>()).add(bucket);
        } else if (selected.containsKey(field)) {
            Set<Bucket> choices = selected.get(field);
            choices.remove(bucket);
            if (choices.isEmpty()) { selected.remove(field); }
        }
    }

    void clear() { selected.clear(); }
    void clear(Field field) { selected.remove(field); }

    Map<Field, Set<Bucket>> snapshot() {
        Map<Field, Set<Bucket>> copy = new EnumMap<>(Field.class);
        selected.forEach((field, buckets) -> copy.put(field, Set.copyOf(buckets)));
        return Map.copyOf(copy);
    }

    void restore(Map<Field, Set<Bucket>> snapshot) {
        selected.clear();
        snapshot.forEach((field, buckets) -> selected.put(field, new HashSet<>(buckets)));
    }

    List<Bucket> options(Field field, List<BrowserItem> items, LocalDate today) {
        return java.util.stream.Stream.concat(items.stream().map(item -> bucket(field, item, today)),
                        selected.getOrDefault(field, Set.of()).stream())
                .distinct().sorted(Comparator.comparingInt(Bucket::order)
                        .thenComparing(Bucket::label, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    boolean includes(BrowserItem item, LocalDate today) {
        return selected.entrySet().stream().allMatch(entry -> entry.getValue().contains(bucket(entry.getKey(), item, today)));
    }

    private Bucket bucket(Field field, BrowserItem item, LocalDate today) {
        return switch (field) {
            case NAME -> name(item.name());
            case MODIFIED -> item.modified() == null ? new Bucket(9, "未指定")
                    : date(item.modified().toInstant().atZone(ZoneId.systemDefault()).toLocalDate(), today);
            case TYPE -> new Bucket(0, item.type());
            case SIZE -> size(item.size());
            default -> throw new IllegalArgumentException("Unsupported filter column: " + field);
        };
    }

    private Bucket name(String value) {
        String name = value.stripLeading();
        if (name.isEmpty()) { return new Bucket(8, "其他"); }
        int first = name.codePointAt(0);
        if (Character.isDigit(first)) { return new Bucket(0, "0–9"); }
        int upper = initial(Character.toString(first));
        if (upper >= 'A' && upper <= 'H') { return new Bucket(1, "A–H"); }
        if (upper >= 'I' && upper <= 'P') { return new Bucket(2, "I–P"); }
        if (upper >= 'Q' && upper <= 'Z') { return new Bucket(3, "Q–Z"); }
        if (Character.UnicodeScript.of(first) == Character.UnicodeScript.HAN) {
            int letter = initials.computeIfAbsent(first, this::pinyinInitial);
            if (letter >= 'A' && letter <= 'F') { return new Bucket(4, "拼音 A–F"); }
            if (letter >= 'G' && letter <= 'L') { return new Bucket(5, "拼音 G–L"); }
            if (letter >= 'M' && letter <= 'S') { return new Bucket(6, "拼音 M–S"); }
            if (letter >= 'T' && letter <= 'Z') { return new Bucket(7, "拼音 T–Z"); }
        }
        return new Bucket(8, "其他");
    }

    private int pinyinInitial(int character) {
        if (pinyin == null) { pinyin = Transliterator.getInstance("Han-Latin"); }
        return initial(pinyin.transliterate(Character.toString(character)));
    }

    private static int initial(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD);
        return normalized.isEmpty() ? 0 : Character.toUpperCase(normalized.codePointAt(0));
    }

    private static Bucket date(LocalDate date, LocalDate today) {
        if (date.isAfter(today)) { return new Bucket(8, "将来"); }
        if (date.equals(today)) { return new Bucket(7, "今天"); }
        if (date.equals(today.minusDays(1))) { return new Bucket(6, "昨天"); }
        LocalDate thisWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        if (!date.isBefore(thisWeek)) { return new Bucket(5, "本周早些时候"); }
        if (!date.isBefore(thisWeek.minusWeeks(1))) { return new Bucket(4, "上周"); }
        LocalDate thisMonth = today.withDayOfMonth(1);
        if (!date.isBefore(thisMonth)) { return new Bucket(3, "这个月的早些时候"); }
        if (!date.isBefore(thisMonth.minusMonths(1))) { return new Bucket(2, "上月"); }
        if (date.getYear() == today.getYear()) { return new Bucket(1, "今年早些时候"); }
        return new Bucket(0, "很久以前");
    }

    private static Bucket size(Long bytes) {
        if (bytes == null) { return new Bucket(7, "未指定"); }
        if (bytes == 0) { return new Bucket(0, "空（0 KB）"); }
        if (bytes <= 16 * KIB) { return new Bucket(1, "极小（0–16 KB）"); }
        if (bytes <= MIB) { return new Bucket(2, "小（16 KB–1 MB）"); }
        if (bytes <= 128 * MIB) { return new Bucket(3, "中等（1–128 MB）"); }
        if (bytes <= GIB) { return new Bucket(4, "大（128 MB–1 GB）"); }
        if (bytes <= 4 * GIB) { return new Bucket(5, "很大（1–4 GB）"); }
        return new Bucket(6, "超大（超过 4 GB）");
    }
}
