package com.chanakya.hsapi.graphql;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

public final class GraphQlUtils {

    private GraphQlUtils() {}

    public static <T> List<T> filterAndSort(List<T> items, String startDate, String endDate,
                                             String sortOrder, Function<T, LocalDate> dateExtractor) {
        LocalDate start = startDate != null ? LocalDate.parse(startDate) : null;
        LocalDate end = endDate != null ? LocalDate.parse(endDate) : null;

        var stream = items.stream();
        if (start != null) {
            stream = stream.filter(i -> {
                LocalDate d = dateExtractor.apply(i);
                return d != null && !d.isBefore(start);
            });
        }
        if (end != null) {
            stream = stream.filter(i -> {
                LocalDate d = dateExtractor.apply(i);
                return d != null && !d.isAfter(end);
            });
        }

        Comparator<T> comp = Comparator.comparing(dateExtractor,
            Comparator.nullsLast(Comparator.naturalOrder()));
        if (!"ASC".equalsIgnoreCase(sortOrder)) {
            comp = comp.reversed();
        }

        return stream.sorted(comp).toList();
    }
}
