package dev.radarfcs.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Подписи значков монитора без наложения: ставятся по важности; подпись, которая налезает на уже поставленные,
 * сдвигается на строку вниз или вверх (до двух строк), а если места нет - не рисуется.
 */
final class LabelLayout {
    /** Сдвиги по высоте в строках подписи, по порядку проб. */
    private static final int[] SHIFTS = {0, 1, -1, 2, -2};

    /** Важность: под курсором, назначенная, активная цель, остальное (свои, неактивные, транспондеры). */
    static final int RANK_HOVER = 4, RANK_DESIGNATED = 3, RANK_ACTIVE = 2, RANK_MINOR = 1;

    /** Подпись: прямоугольник с левым краем x, центром по высоте y, шириной w и высотой h; текст, цвет, важность. */
    record Label(float x, float y, float w, float h, String text, int color, int rank) {}

    /** Поставленная подпись: y - итоговый центр по высоте. */
    record Placed(Label label, float y) {}

    private LabelLayout() {}

    static List<Placed> place(List<Label> labels) {
        List<Label> order = new ArrayList<>(labels);
        order.sort(Comparator.comparingInt(Label::rank).reversed());
        List<Placed> out = new ArrayList<>();
        for (Label l : order) {
            for (int k : SHIFTS) {
                float y = l.y() + k * l.h();
                if (free(out, l, y)) {
                    out.add(new Placed(l, y));
                    break;
                }
            }
        }
        return out;
    }

    private static boolean free(List<Placed> placed, Label l, float y) {
        for (Placed p : placed) {
            Label o = p.label();
            if (l.x() < o.x() + o.w() && o.x() < l.x() + l.w() && Math.abs(y - p.y()) < (l.h() + o.h()) / 2) return false;
        }
        return true;
    }
}
