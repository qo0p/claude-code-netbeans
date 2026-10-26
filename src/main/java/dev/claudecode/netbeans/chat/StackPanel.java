package dev.claudecode.netbeans.chat;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

/**
 * A vertical stack whose children always get the full width, and whose
 * height follows the children's wrapped height. Used for the transcript.
 */
final class StackPanel extends JPanel implements Scrollable {

    private static final long serialVersionUID = 1L;

    StackPanel(int gap) {
        setLayout(new StackLayout(gap));
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle r, int orientation, int direction) {
        return 18;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle r, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? Math.max(18, r.height - 36) : r.width;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    static final class StackLayout implements LayoutManager {
        private final int gap;

        StackLayout(int gap) {
            this.gap = gap;
        }

        static int heightFor(Component c, int width) {
            c.setSize(width, Short.MAX_VALUE);
            if (c instanceof Container) {
                layoutTree((Container) c);
            }
            return c.getPreferredSize().height;
        }

        private static void layoutTree(Container c) {
            c.doLayout();
            for (Component child : c.getComponents()) {
                if (child instanceof Container) {
                    layoutTree((Container) child);
                }
            }
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets in = parent.getInsets();
            int w = parent.getWidth() > 0 ? parent.getWidth() : 360;
            int cw = Math.max(50, w - in.left - in.right);
            int h = in.top + in.bottom;
            boolean first = true;
            for (Component c : parent.getComponents()) {
                if (!c.isVisible()) {
                    continue;
                }
                if (!first) {
                    h += gap;
                }
                first = false;
                h += heightFor(c, cw);
            }
            return new Dimension(w, h);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return new Dimension(50, 50);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets in = parent.getInsets();
            int cw = Math.max(50, parent.getWidth() - in.left - in.right);
            int y = in.top;
            for (Component c : parent.getComponents()) {
                if (!c.isVisible()) {
                    continue;
                }
                int h = heightFor(c, cw);
                c.setBounds(in.left, y, cw, h);
                y += h + gap;
            }
        }

        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }
    }
}
