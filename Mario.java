// ============================================================
// MINI MARIO - Gráficos avanzados (Gfx integrado), Skin Sombría y Power-Ups
// ============================================================

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.prefs.Preferences;

public class Mario extends JPanel implements ActionListener, KeyListener {

    // ---------- Constantes ----------
    static final int W = 800, H = 450, GROUND_Y = 370, PW = 28, PH = 42;
    static final double SPEED = 5, GRAVITY = 0.6, JUMP = -13, MAX_FALL = 15;
    static final int COYOTE = 6, JUMP_BUFFER = 6, TILE = 1200;
    static final long STEP_NS = 16_666_667L; // 1/60 s
    static final int FLAG_X = 20000;          // meta a los 2000 m
    static final int BACK_LIMIT = 1000;       // retroceso máximo de 100 m
    static final int CASTLE_X = FLAG_X + 260;
    static final int[] FW_COLORS = {0xFF4040, 0xFFD728, 0x50E0FF, 0x70FF70, 0xFF70E0, 0xFF007F};

    /** Estados del juego */
    enum State { PLAY, FLAG, WALK, WIN, DYING, DEAD }
    /** Pantallas de la aplicación */
    enum Screen { MENU, SHOP, SKINS, LEVEL_SELECT, GAME }
    /** Tipos de Power-Up */
    enum PowerType { SPEED_BOOST, IMMUNITY, JUMP_BOOST }

    static final Font F_TITLE = new Font("Monospaced", Font.BOLD, 64),
            F_MENU = new Font("Monospaced", Font.BOLD, 18), F_POP = new Font("Monospaced", Font.BOLD, 14);

    static final String[] MENU_ITEMS = {"Jugar", "Tienda", "Skins", "Salir"};
    static final String[] SKIN_NAMES = {"Mario Rojo", "Mario Azul", "Mario Verde", "Mario Morado", "Sombra Ígnea"};
    static final int[] SKIN_PRICE = {0, 100, 1000, 5000, 10000};

    static final Color FLY_GLOW = new Color(200, 255, 120, 60), FLY_CORE = new Color(240, 255, 180, 220),
            POLLEN = new Color(255, 255, 255, 110);
    static double ENEMY_MUL = 1;

    /** Texto flotante */
    static final class FloatText {
        double x, y; int life = 50; final String s; final int rgb;
        FloatText(double x, double y, String s, int rgb) { this.x = x; this.y = y; this.s = s; this.rgb = rgb; }
    }

    static final Font F_HUD = new Font("Monospaced", Font.BOLD, 18);
    static final Font F_BIG = new Font("Monospaced", Font.BOLD, 40);
    static final Font F_SMALL = new Font("Monospaced", Font.PLAIN, 18);

    static Color RED, DARK_RED, BLUE, RED_HI, BLUE_HI, BLUE_LO;
    static final Color SKIN_COLOR = new Color(255, 205, 160),
            SHOE = new Color(85, 45, 20), HAIR = new Color(70, 35, 15),
            GOLD = new Color(255, 205, 40), GOLD_DARK = new Color(210, 150, 20);

    // ---------- Tipos de datos ligeros ----------
    static final class Plat {
        final int x, y, w, h;
        final boolean brick;
        Plat(int x, int y, int w, int h) { this(x, y, w, h, false); }
        Plat(int x, int y, int w, int h, boolean brick) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.brick = brick;
        }
    }

    static final class Coin {
        final double x, y; boolean got;
        Coin(double x, double y) { this.x = x; this.y = y; }
    }

    static final class PowerUp {
        final double x, y;
        final PowerType type;
        boolean got;
        PowerUp(double x, double y, PowerType type) { this.x = x; this.y = y; this.type = type; }
    }

    static boolean overlap(double px, double py, int pw, int ph, Plat p) {
        return px < p.x + p.w && px + pw > p.x && py < p.y + p.h && py + ph > p.y;
    }

    /** ENEMIGO */
    static final class Enemy {
        final boolean spiky;
        final int w = 28, h = 26;
        double x, y, vy;
        int dir = -1, squashT;
        boolean squashed, remove;

        Enemy(double x, boolean spiky) { this.x = x; this.y = GROUND_Y - h; this.spiky = spiky; }

        void squash() { squashed = true; squashT = 25; }

        static boolean solidAt(List<Plat> ps, double px, double py) {
            for (Plat p : ps) if (px >= p.x && px <= p.x + p.w && py >= p.y && py <= p.y + p.h) return true;
            return false;
        }

        void update(List<Plat> ps) {
            if (squashed) { if (--squashT <= 0) remove = true; return; }
            vy = Math.min(vy + GRAVITY, MAX_FALL);
            y += vy;
            boolean ground = false;
            for (Plat p : ps) if (overlap(x, y, w, h, p)) {
                if (vy > 0) { y = p.y - h; ground = true; } else y = p.y + p.h;
                vy = 0;
            }
            x += dir * (spiky ? 0.9 : 1.3) * ENEMY_MUL;
            boolean turn = false;
            for (Plat p : ps) if (overlap(x, y, w, h, p)) { x = dir > 0 ? p.x - w : p.x + p.w; turn = true; }
            if (ground && !solidAt(ps, x + (dir > 0 ? w + 2 : -2), y + h + 2)) turn = true;
            if (turn) dir = -dir;
        }
    }

    // ============================================================
    // GFX: renderizado avanzado (luz, brillos, contornos, parallax, post-proceso)
    // ============================================================
    static final class Gfx {
        private Gfx() {}

        static final Color OUT = new Color(25, 12, 10, 210);
        static final Stroke S2 = new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

        static int hs(int v) { v ^= v >>> 16; v *= 0x45d9f3b; v ^= v >>> 16; return v & 0x7fffffff; }

        static boolean night(Mario m) { return (m.themeBuilt % 2 != 0) || m.themeBuilt == 5; }

        static void glow(Graphics2D g, double cx, double cy, float r, int rgb, int alpha) {
            if (r <= 0) return;
            g.setPaint(new RadialGradientPaint(new Point2D.Double(cx, cy), r, new float[]{0f, 1f},
                    new Color[]{new Color((alpha << 24) | (rgb & 0xFFFFFF), true), new Color(rgb & 0xFFFFFF, true)}));
            g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        }

        static void fo(Graphics2D g, Shape s, Paint p) {
            g.setPaint(p); g.fill(s);
            Stroke o = g.getStroke();
            g.setStroke(S2); g.setColor(OUT); g.draw(s);
            g.setStroke(o);
        }

        // ---------------- TERRENO
        static void plat(Graphics2D g, Mario m, Plat p, int cam) {
            if (p.brick) { brick(g, p, cam); return; }
            int px = p.x - cam;
            int from = Math.max(p.x, cam), to = Math.min(p.x + p.w, cam + Mario.W);
            for (int tx = from - (from & 63); tx < to; tx += 64) {
                int x0 = Math.max(tx, from), x1 = Math.min(tx + 64, to);
                g.drawImage(m.dirt, x0 - cam, p.y, x1 - cam, p.y + p.h, x0 - tx, 0, x1 - tx, p.h, null);
            }
            for (int tx = from - (from % 48); tx < to; tx += 48) {
                int h = hs(tx * 31 + 7);
                int rx = tx + h % 40, ry = p.y + 16 + (h >> 8) % Math.max(1, p.h - 28), rw = 8 + (h >> 4) % 10;
                if (rx < p.x || rx + rw > p.x + p.w) continue;
                g.setColor(new Color(0, 0, 0, 55)); g.fill(new Ellipse2D.Double(rx - cam + 1, ry + 2, rw, rw * 0.6));
                g.setPaint(new GradientPaint(0, ry, new Color(170, 150, 125), 0, ry + rw * 0.6f, new Color(95, 80, 70)));
                g.fill(new Ellipse2D.Double(rx - cam, ry, rw, rw * 0.6));
            }
            g.setPaint(new GradientPaint(0, p.y, new Color(0, 0, 0, 0), 0, p.y + p.h, new Color(0, 0, 0, 150)));
            g.fillRect(px, p.y, p.w, p.h);
            g.setPaint(new GradientPaint(0, p.y, new Color(0, 0, 0, 110), 0, p.y + 16, new Color(0, 0, 0, 0)));
            g.fillRect(px, p.y, p.w, 16);
            g.setColor(new Color(0, 0, 0, 60)); g.fillRect(px, p.y, 3, p.h); g.fillRect(px + p.w - 3, p.y, 3, p.h);
            if (p.h < 80) { g.setColor(new Color(0, 0, 0, 90)); g.fillRect(px, p.y + p.h - 4, p.w, 4); }

            double f = night(m) ? 0.55 : 1.0;
            g.setPaint(new GradientPaint(0, p.y - 3, new Color((int) (95 * f), (int) (210 * f), (int) (65 * f)),
                    0, p.y + 7, new Color((int) (40 * f), (int) (120 * f), (int) (40 * f))));
            g.fillRect(px, p.y - 3, p.w, 10);
            for (int wx = from - (from % 3); wx < to; wx += 3) {
                int h = hs(wx), bh = 5 + h % 8;
                double sw = Math.sin(m.tick * 0.06 + wx * 0.15) * 2.4;
                int bx = wx - cam;
                int gr = 150 + h % 90;
                g.setColor(new Color((int) ((50 + h % 40) * f), (int) (gr * f), (int) (40 * f)));
                g.fillPolygon(new int[]{bx, bx + 3, bx + 1 + (int) sw}, new int[]{p.y + 1, p.y + 1, p.y - bh}, 3);
            }
            g.setColor(new Color(255, 255, 200, 70)); g.fillRect(px, p.y - 3, p.w, 1);
        }

        static void brick(Graphics2D g, Plat p, int cam) {
            int px = p.x - cam;
            for (int yy = p.y; yy < p.y + p.h; yy += 20) {
                int off = ((yy - p.y) / 20) % 2 == 0 ? 0 : 20;
                for (int xx = -off; xx < p.w; xx += 40) {
                    int bx = Math.max(0, xx), bw = Math.min(p.w, xx + 40) - bx;
                    if (bw <= 0) continue;
                    int sh = hs(xx * 31 + yy) % 26;
                    g.setPaint(new GradientPaint(0, yy, new Color(220 - sh, 112 - sh / 2, 66), 0, yy + 20, new Color(150 - sh, 70, 38)));
                    g.fillRect(px + bx, yy, bw, 20);
                    g.setColor(new Color(255, 225, 175, 120)); g.drawLine(px + bx, yy, px + bx + bw - 1, yy);
                    g.setColor(new Color(0, 0, 0, 120));
                    g.drawLine(px + bx, yy + 19, px + bx + bw - 1, yy + 19);
                    g.drawLine(px + bx + bw - 1, yy, px + bx + bw - 1, yy + 19);
                }
            }
        }

        // ---------------- OBJETOS
        static void coin(Graphics2D g, Mario m, Coin c, int cam, double t) {
            int sx = (int) (c.x - cam);
            int sy = (int) (c.y + Math.sin(t + c.x * 0.05) * 3);
            glow(g, sx, sy, 24, 0xFFD728, 80);
            int w = (int) (Math.abs(Math.cos(t * 1.5 + c.x * 0.03)) * 11) + 3;
            Ellipse2D e = new Ellipse2D.Double(sx - w / 2.0 - 1, sy - 10, w + 2, 20);
            g.setPaint(new GradientPaint(sx - w / 2f, sy - 10, new Color(255, 245, 140), sx + w / 2f, sy + 10, new Color(215, 135, 10)));
            g.fill(e);
            g.setColor(new Color(140, 85, 0)); g.draw(e);
            if (w > 7) {
                g.setColor(new Color(255, 205, 45)); g.fill(new Ellipse2D.Double(sx - w / 2.0 + 2, sy - 7, w - 4, 14));
                g.setColor(new Color(190, 120, 10)); g.fillRect(sx - 1, sy - 4, 2, 8);
            }
            if ((m.tick / 5 + (int) c.x) % 10 == 0) {
                g.setColor(new Color(255, 255, 255, 230));
                g.fillRect(sx - 1, sy - 15, 2, 8); g.fillRect(sx - 4, sy - 12, 8, 2);
            }
        }

        static void power(Graphics2D g, Mario m, PowerUp pw, int cam) {
            double cx = pw.x - cam + 10, cy = pw.y + 10 + Math.sin(m.tick * 0.1 + pw.x) * 3;
            int rgb = switch (pw.type) { case SPEED_BOOST -> 0xFFD728; case IMMUNITY -> 0x50E0FF; case JUMP_BOOST -> 0xFF7040; };
            g.setColor(new Color(0, 0, 0, 60)); g.fill(new Ellipse2D.Double(cx - 11, pw.y + 26, 22, 6));
            glow(g, cx, cy, 38, rgb, 120);
            Color col = new Color(rgb);
            fo(g, new Ellipse2D.Double(cx - 12, cy - 12, 24, 24),
                    new RadialGradientPaint(new Point2D.Double(cx - 4, cy - 5), 16f, new float[]{0f, 1f}, new Color[]{Color.WHITE, col}));
            Stroke os = g.getStroke();
            g.setStroke(new BasicStroke(2f)); g.setColor(new Color(255, 255, 255, 170));
            g.draw(new Arc2D.Double(cx - 17, cy - 17, 34, 34, m.tick * 4, 80, Arc2D.OPEN));
            g.draw(new Arc2D.Double(cx - 17, cy - 17, 34, 34, m.tick * 4 + 180, 80, Arc2D.OPEN));
            g.setStroke(os);
            AffineTransform old = g.getTransform();
            g.translate(cx, cy);
            g.setColor(new Color(40, 30, 20, 230));
            switch (pw.type) {
                case SPEED_BOOST -> g.fillPolygon(new int[]{2, -5, 0, -2, 5, 0}, new int[]{-8, 1, 1, 8, -2, -2}, 6);
                case IMMUNITY -> g.fillPolygon(new int[]{-6, 0, 6, 5, 0, -5}, new int[]{-5, -8, -5, 3, 8, 3}, 6);
                case JUMP_BOOST -> g.fillPolygon(new int[]{0, 7, 3, 3, -3, -3, -7}, new int[]{-8, 0, 0, 8, 8, 0, 0}, 7);
            }
            g.setTransform(old);
        }

        static void enemy(Graphics2D g, Mario m, Enemy en, int cam) {
            int sx = (int) en.x - cam, sy = (int) en.y, w = en.w, h = en.h;
            if (sx < -40 || sx > Mario.W + 40) return;
            g.setColor(new Color(0, 0, 0, 70)); g.fillOval(sx - 2, sy + h - 4, w + 4, 8);
            if (en.squashed) { fo(g, new Ellipse2D.Double(sx - 3, sy + h - 9, w + 6, 9), new Color(130, 75, 35)); return; }
            int f = (m.tick / 8) % 2 == 0 ? 3 : -3;
            Color foot = new Color(55, 28, 14);
            fo(g, new Ellipse2D.Double(sx - 1 + f, sy + h - 8, 14, 8), foot);
            fo(g, new Ellipse2D.Double(sx + w - 13 - f, sy + h - 8, 14, 8), foot);
            Stroke os = g.getStroke();
            if (en.spiky) {
                for (int i = 0; i < 5; i++) {
                    int bx = sx + i * 6 - 1;
                    fo(g, new Polygon(new int[]{bx, bx + 3, bx + 6}, new int[]{sy + 9, sy - 5, sy + 9}, 3), new Color(245, 245, 235));
                }
                fo(g, new Ellipse2D.Double(sx - 2, sy + 3, w + 4, h - 9),
                        new GradientPaint(0, sy, new Color(245, 75, 60), 0, sy + h, new Color(130, 15, 20)));
                glow(g, sx + 9, sy + 15, 9, 0xFFDC3C, 110); glow(g, sx + w - 9, sy + 15, 9, 0xFFDC3C, 110);
                g.setColor(new Color(255, 225, 70)); g.fillOval(sx + 5, sy + 11, 7, 7); g.fillOval(sx + w - 12, sy + 11, 7, 7);
                g.setColor(Color.BLACK); g.fillOval(sx + 7 + en.dir, sy + 13, 3, 4); g.fillOval(sx + w - 10 + en.dir, sy + 13, 3, 4);
                g.setStroke(new BasicStroke(2.2f)); g.setColor(Color.BLACK);
                g.drawLine(sx + 3, sy + 8, sx + 12, sy + 12); g.drawLine(sx + w - 3, sy + 8, sx + w - 12, sy + 12);
            } else {
                fo(g, new Ellipse2D.Double(sx - 2, sy, w + 4, h - 6),
                        new GradientPaint(0, sy, new Color(210, 140, 75), 0, sy + h - 6, new Color(115, 65, 28)));
                g.setColor(new Color(245, 215, 170)); g.fillRoundRect(sx + 6, sy + h - 14, w - 12, 8, 6, 6);
                g.setColor(new Color(255, 255, 255, 60)); g.fill(new Ellipse2D.Double(sx + 3, sy + 2, 14, 6));
                fo(g, new Ellipse2D.Double(sx + 4, sy + 7, 9, 10), Color.WHITE);
                fo(g, new Ellipse2D.Double(sx + w - 13, sy + 7, 9, 10), Color.WHITE);
                g.setColor(Color.BLACK); g.fillOval(sx + 7 + en.dir, sy + 10, 4, 6); g.fillOval(sx + w - 11 + en.dir, sy + 10, 4, 6);
                g.setStroke(new BasicStroke(2.2f));
                g.drawLine(sx + 3, sy + 5, sx + 13, sy + 9); g.drawLine(sx + w - 3, sy + 5, sx + w - 13, sy + 9);
            }
            g.setStroke(os);
        }

        // ---------------- JUGADOR
        static void player(Graphics2D g, Mario m, int sx, int sy) {
            final int PW = Mario.PW, PH = Mario.PH;
            double gy = m.groundBelow();
            if (gy < Mario.H) {
                double hgt = Math.max(0, gy - (m.y + PH));
                double shw = PW * 1.3 * (1 - Math.min(0.5, hgt / 300));
                int a = (int) (255 * Math.max(0.05, 0.38 - hgt / 400));
                g.setColor(new Color(0, 0, 0, a / 2)); g.fill(new Ellipse2D.Double(sx + PW / 2.0 - shw / 2 - 3, gy - 5, shw + 6, 10));
                g.setColor(new Color(0, 0, 0, a)); g.fill(new Ellipse2D.Double(sx + PW / 2.0 - shw / 2, gy - 3, shw, 6));
            }
            float pulse = (float) (Math.sin(m.tick * 0.15) * 4);
            if (m.immunityTimer > 0) {
                glow(g, sx + PW / 2.0, sy + PH / 2.0, 44 + pulse, 0x50E0FF, 140);
                Stroke os = g.getStroke();
                g.setStroke(new BasicStroke(2f)); g.setColor(new Color(160, 240, 255, 170));
                g.draw(new Ellipse2D.Double(sx - 10 - pulse / 2, sy - 10 - pulse / 2, PW + 20 + pulse, PH + 20 + pulse));
                g.setStroke(os);
            }
            boolean s4 = m.skin == 4;
            if (s4) glow(g, sx + PW / 2.0, sy + PH / 2.0, 48 + pulse, 0xFF1493, 130);

            AffineTransform old = g.getTransform();
            g.translate(sx + PW / 2.0, sy + PH);
            double st = Math.min(0.15, Math.abs(m.vy) * 0.01) + m.squash;
            g.scale(m.facing / (1 + st), 1 + st);

            boolean run = m.onGround && Math.abs(m.vx) > 0.5;
            int sw = run ? (int) (Math.sin(m.animT) * 6) : 0, lift = m.onGround ? 0 : 3;
            Paint shoe = new GradientPaint(0, -5, new Color(125, 72, 36), 0, 1, Mario.SHOE);

            fo(g, new RoundRectangle2D.Double(-9 + sw, -14 - lift, 8, 14, 4, 4), Mario.BLUE_LO);
            fo(g, new RoundRectangle2D.Double(-11 + sw, -5 - lift, 12, 6, 5, 5), shoe);
            fo(g, new RoundRectangle2D.Double(-11, -29, 22, 12, 6, 6), new GradientPaint(0, -29, Mario.RED_HI, 0, -17, Mario.RED));
            fo(g, new RoundRectangle2D.Double(-11, -22, 22, 14, 6, 6), new GradientPaint(0, -22, Mario.BLUE_HI, 0, -8, Mario.BLUE));
            g.setColor(new Color(255, 255, 255, 45)); g.fillRoundRect(-9, -21, 6, 8, 4, 4);
            g.setColor(s4 ? new Color(255, 105, 180) : Mario.GOLD);
            g.fillOval(-7, -20, 4, 4); g.fillOval(3, -20, 4, 4);
            fo(g, new RoundRectangle2D.Double(1 - sw, -14, 8, 14, 4, 4), Mario.BLUE);
            fo(g, new RoundRectangle2D.Double(-1 - sw, -5, 12, 6, 5, 5), shoe);
            int arm = run ? (int) (Math.sin(m.animT + Math.PI) * 5) : (m.onGround ? 0 : -5);
            fo(g, new RoundRectangle2D.Double(2 + arm, -27, 7, 11, 4, 4), Mario.RED);
            fo(g, new Ellipse2D.Double(3 + arm, -18, 7, 7), s4 ? new Color(255, 20, 147) : Color.WHITE);
            fo(g, new Ellipse2D.Double(-10, -41, 20, 18), new RadialGradientPaint(new Point2D.Double(-3, -36), 14f,
                    new float[]{0f, 1f}, new Color[]{new Color(255, 228, 195), new Color(232, 168, 122)}));
            g.setColor(new Color(240, 180, 140)); g.fillOval(7, -34, 5, 6);
            g.setColor(Mario.HAIR); g.fillRoundRect(-9, -38, 6, 8, 3, 3);
            g.setColor(Color.WHITE); g.fillOval(1, -37, 6, 7);
            g.setColor(s4 ? new Color(255, 0, 128) : Color.BLACK); g.fillOval(3, -36, 3, 5);
            g.setColor(new Color(255, 255, 255, 220)); g.fillRect(4, -36, 1, 1);
            fo(g, new Ellipse2D.Double(5, -34, 8, 8), Mario.SKIN_COLOR);
            g.setColor(new Color(255, 120, 110, 70)); g.fillOval(0, -31, 5, 4);
            fo(g, new RoundRectangle2D.Double(3, -30, 9, 4, 3, 3), Mario.HAIR);
            fo(g, new Arc2D.Double(-12, -49, 24, 18, 0, 180, Arc2D.CHORD), new GradientPaint(0, -49, Mario.RED_HI, 0, -34, Mario.RED));
            fo(g, new RoundRectangle2D.Double(-3, -42, 16, 5, 4, 4), Mario.DARK_RED);
            fo(g, new Ellipse2D.Double(-4, -47, 8, 8), s4 ? new Color(255, 105, 180) : Color.WHITE);
            g.setTransform(old);
        }

        // ---------------- PARTÍCULAS
        static void particles(Graphics2D g, Mario m, int cam) {
            for (int i = 0; i < m.pCount; i++) {
                float k = Math.min(1f, m.pLife[i] / m.pMax[i]);
                float s = m.pSize[i] * (0.5f + k * 0.7f);
                int rgb = m.pRGB[i] & 0xFFFFFF;
                double px = m.pX[i] - cam, py = m.pY[i];
                g.setColor(new Color(((int) (255 * k * 0.3) << 24) | rgb, true));
                g.fill(new Ellipse2D.Double(px - s, py - s, 2 * s, 2 * s));
                g.setColor(new Color(((int) (255 * k * 0.9) << 24) | rgb, true));
                g.fill(new Ellipse2D.Double(px - s / 2, py - s / 2, s, s));
            }
        }

        // ---------------- FONDO: ÁRBOLES
        static BufferedImage trees(int lvl, boolean night) {
            BufferedImage im = Mario.img(Mario.TILE, Mario.H);
            Graphics2D g = im.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Color c = lvl == 5 ? new Color(45, 12, 38) : night ? new Color(14, 38, 55) : new Color(38, 105, 72);
            Color haze = lvl == 5 ? new Color(90, 15, 55) : night ? new Color(40, 50, 100) : new Color(160, 210, 200);
            for (int i = 0; i < 26; i++) {
                int x = (i * Mario.TILE / 26) + hs(i * 17 + lvl) % 30;
                int h = 90 + hs(i * 5 + lvl * 3) % 90;
                for (int k = 0; k <= 1; k++) {
                    int xx = x - k * Mario.TILE;
                    if (k == 1 && x < Mario.TILE - 80) continue;
                    pine(g, xx, Mario.GROUND_Y + 4, h, c);
                }
            }
            g.setPaint(new GradientPaint(0, Mario.GROUND_Y - 110, new Color(haze.getRed(), haze.getGreen(), haze.getBlue(), 0),
                    0, Mario.GROUND_Y, new Color(haze.getRed(), haze.getGreen(), haze.getBlue(), 120)));
            g.fillRect(0, Mario.GROUND_Y - 110, Mario.TILE, 114);
            g.dispose();
            return im;
        }

        static void pine(Graphics2D g, int x, int base, int h, Color c) {
            g.setColor(c.darker()); g.fillRect(x - 3, base - h / 6, 6, h / 6);
            for (int i = 0; i < 4; i++) {
                int apex = base - h + i * h / 5, bot = apex + h / 2, half = (int) (h * 0.13 * (i + 2) / 2.0);
                g.setPaint(new GradientPaint(x - half, 0, c.brighter(), x + half, 0, c));
                g.fillPolygon(new int[]{x - half, x, x + half}, new int[]{bot, apex, bot}, 3);
            }
        }

        // ---------------- POST-PROCESO
        static void post(Graphics2D g, Mario m) {
            boolean n = night(m);
            int GY = Mario.GROUND_Y;
            if (!n) {
                for (int i = 0; i < 6; i++) {
                    double a = 1.7 + i * 0.2 + Math.sin(m.tick * 0.004 + i) * 0.04, r = 900;
                    Path2D.Double p = new Path2D.Double();
                    p.moveTo(620, 100);
                    p.lineTo(620 + Math.cos(a) * r, 100 + Math.sin(a) * r);
                    p.lineTo(620 + Math.cos(a + 0.07) * r, 100 + Math.sin(a + 0.07) * r);
                    p.closePath();
                    g.setColor(new Color(255, 245, 200, 15)); g.fill(p);
                }
                g.setColor(new Color(255, 200, 120, 14)); g.fillRect(0, 0, Mario.W, Mario.H);
            } else {
                glow(g, 620, 100, 130, m.themeBuilt == 5 ? 0xFF4080 : 0xB4C8FF, 55);
                g.setColor(m.themeBuilt == 5 ? new Color(120, 0, 40, 40) : new Color(40, 60, 140, 28));
                g.fillRect(0, 0, Mario.W, Mario.H);
            }
            Color fog = n ? new Color(60, 70, 140) : new Color(210, 235, 255);
            g.setPaint(new GradientPaint(0, GY - 70, new Color(fog.getRed(), fog.getGreen(), fog.getBlue(), 0),
                    0, GY, new Color(fog.getRed(), fog.getGreen(), fog.getBlue(), 45)));
            g.fillRect(0, GY - 70, Mario.W, 70);
        }
    }

    // ---------- Estado ----------
    final List<Plat> platforms = new ArrayList<>();
    final List<Coin> coins = new ArrayList<>();
    final List<Enemy> enemies = new ArrayList<>();
    final List<PowerUp> powerUps = new ArrayList<>();
    final Random rnd = new Random();
    final Random gen = new Random();

    double x, y, vx, vy, camX;
    int genEnd, facing = 1, coyote, jumpBuf, tick, maxDistance, coinCount;
    double animT, squash, shake;
    boolean left, right, jumpKey, onGround;
    State state = State.PLAY;
    double maxX, flagY;
    int leftLimit, score, winTimer;

    int speedTimer = 0;
    int immunityTimer = 0;
    int jumpTimer = 0;

    Screen screen = Screen.MENU;
    int sel, shopSel = 1, skinSel, levelSel = 0, skin, level, totalCoins, themeBuilt, msgT;
    final boolean[] unlockedLevels = {true, false, false, false, false, false};
    final boolean[] owned = {true, false, false, false, false};
    double fade = 1;
    String msg = "";
    final List<FloatText> pops = new ArrayList<>();
    final Preferences prefs = Preferences.userNodeForPackage(Mario.class);
    boolean finalBuilt;

    // Partículas
    static final int MAXP = 1000;
    final float[] pX = new float[MAXP], pY = new float[MAXP], pVX = new float[MAXP],
            pVY = new float[MAXP], pLife = new float[MAXP], pMax = new float[MAXP], pSize = new float[MAXP];
    final int[] pRGB = new int[MAXP];
    int pCount;

    // Sprites
    BufferedImage sky, clouds, far, mid, near, trees, dirt;
    BufferedImage vignette;

    long lastNs = System.nanoTime(), acc = 0;

    // ============================================================
    public Mario() {
        setPreferredSize(new Dimension(W, H));
        setFocusable(true);
        addKeyListener(this);
        setDoubleBuffered(true);
        load();
        applySkin(skin);
        Runtime.getRuntime().addShutdownHook(new Thread(this::save));
        buildSprites(0);
        reset();
        new Timer(4, this).start();
    }

    void reset() {
        platforms.clear(); coins.clear(); enemies.clear(); powerUps.clear(); pops.clear(); pCount = 0;
        speedTimer = immunityTimer = jumpTimer = 0;
        gen.setSeed(7919L * (level + 1));
        platforms.add(new Plat(0, GROUND_Y, 600, H - GROUND_Y));
        genEnd = 600;
        x = 100; y = GROUND_Y - PH; vx = vy = 0; camX = 0;
        maxDistance = 0; coinCount = 0; squash = 0; shake = 0;
        state = State.PLAY; finalBuilt = false; maxX = 0; leftLimit = 0; score = 0;
        flagY = GROUND_Y - 290; facing = 1; winTimer = 0;
    }

    // ============================================================
    // GENERACIÓN PROCEDURAL
    // ============================================================
    void generate() {
        while (!finalBuilt && genEnd < camX + W + 300) {
            boolean isImpossible = (level == 5);
            int extraGap = level * 20 + (level >= 4 ? 40 : 0) + (isImpossible ? 50 : 0);
            int gapChance = isImpossible ? 1 : (3 - (level >= 3 ? 1 : 0));

            int gap = gen.nextInt(Math.max(1, gapChance)) == 0 ? 80 + gen.nextInt(70 + extraGap) : 0;
            if (isImpossible) gap = Math.min(gap, 210);

            int minLen = isImpossible ? 60 : Math.max(90, (220 - level * 20));
            int len = Math.max(minLen, minLen + gen.nextInt(isImpossible ? 80 : 180));

            if (startAndLenCheck(gap, len)) break;

            int start = genEnd + gap;
            platforms.add(new Plat(start, GROUND_Y, len, H - GROUND_Y));

            int enemyChance = 40 + level * 10 + (isImpossible ? 45 : 0);
            if (start > 700 && len > 80 && gen.nextInt(100) < enemyChance) {
                boolean isSpiky = gen.nextInt(100) < (20 + level * 15 + (isImpossible ? 35 : 0));
                enemies.add(new Enemy(start + 15 + gen.nextInt(Math.max(1, len - 30)), isSpiky));
            }

            if (gen.nextBoolean()) {
                int fw = 60 + gen.nextInt(50);
                int fx = start + gen.nextInt(Math.max(1, len - fw));
                int fy = GROUND_Y - 75 - gen.nextInt(45);
                platforms.add(new Plat(fx, fy, fw, 18));
                for (int i = 0; i < 3; i++) coins.add(new Coin(fx + fw / 2.0 - 28 + i * 28, fy - 26));

                if (gen.nextInt(100) < 25) {
                    PowerType type = PowerType.values()[gen.nextInt(PowerType.values().length)];
                    powerUps.add(new PowerUp(fx + fw / 2.0 - 12, fy - 32, type));
                }
            } else if (gen.nextInt(3) == 0) {
                int n = 3 + gen.nextInt(3);
                int cx = start + 10 + gen.nextInt(Math.max(1, len - 100));
                for (int i = 0; i < n; i++)
                    coins.add(new Coin(cx + i * 28, GROUND_Y - 35 - Math.sin(i * Math.PI / (n - 1)) * 35));
            }
            genEnd = start + len;
        }
        if (tick % 30 == 0) {
            platforms.removeIf(p -> p.x + p.w < leftLimit - 100);
            coins.removeIf(c -> c.got || c.x < leftLimit - 100);
            enemies.removeIf(en -> en.remove || en.x < leftLimit - 100 || en.y > H + 100);
            powerUps.removeIf(pw -> pw.got || pw.x < leftLimit - 100);
        }
    }

    private boolean startAndLenCheck(int gap, int len) {
        int start = genEnd + gap;
        if (start + len >= FLAG_X - 400) { buildFinale(); return true; }
        return false;
    }

    // ============================================================
    // GAME LOOP Y LÓGICA
    // ============================================================
    @Override public void actionPerformed(ActionEvent e) {
        long now = System.nanoTime();
        acc = Math.min(acc + (now - lastNs), 100_000_000L);
        lastNs = now;
        boolean stepped = false;
        while (acc >= STEP_NS) {
            update();
            updateParticles();
            acc -= STEP_NS; stepped = true;
        }
        if (stepped) repaint();
    }

    void buildFinale() {
        platforms.add(new Plat(genEnd, GROUND_Y, FLAG_X + 900 - genEnd, H - GROUND_Y));
        for (int i = 0; i < 6; i++)
            platforms.add(new Plat(FLAG_X - 320 + i * 40, GROUND_Y - (i + 1) * 40, 40, (i + 1) * 40, true));
        genEnd = FLAG_X + 900;
        finalBuilt = true;
    }

    void updateEnemies() {
        for (Enemy en : enemies) {
            if (en.x > camX + W + 400 || en.x < camX - 300) continue;
            en.update(platforms);
            if (en.squashed || state != State.PLAY) continue;
            if (x < en.x + en.w && x + PW > en.x && y < en.y + en.h && y + PH > en.y) {
                boolean stomp = vy > 0 && (y + PH - vy) <= en.y + 8 && !en.spiky;
                if (stomp) {
                    en.squash();
                    double jumpPower = JUMP * (jumpTimer > 0 ? 1.35 : 1.0);
                    vy = jumpKey ? jumpPower : jumpPower * 0.6;
                    onGround = false; squash = 0.25; score += 100;
                    pop(en.x + en.w / 2.0, en.y - 6, "+100", 0xFFFFFF);
                    burst((float) (en.x + en.w / 2), (float) (en.y + en.h), 8, 0xFFFFFF, 2f);
                } else if (immunityTimer > 0) {
                    en.squash();
                    pop(en.x + en.w / 2.0, en.y - 6, "¡INMUNE!", 0x50E0FF);
                    burst((float) (en.x + en.w / 2), (float) (en.y + en.h), 12, 0x50E0FF, 3f);
                } else killPlayer();
            }
        }
    }

    void killPlayer() {
        if (state != State.PLAY) return;
        state = State.DYING; vy = -11; vx = 0;
    }

    void updateDying() {
        tick++; vy += GRAVITY; y += vy;
        if (y > H + 80) { state = State.DEAD; save(); }
    }

    void checkEnd() {
        if (state == State.PLAY && x + PW >= FLAG_X - 2) {
            int h = (int) Math.max(0, GROUND_Y - (y + PH));
            score += 100 + 100 * (h / 60);
            state = State.FLAG; winTimer = 0;
            x = FLAG_X - PW + 2; vx = vy = 0; facing = 1;
            burst((float) FLAG_X, (float) (y + PH / 2), 12, 0xFFFFFF, 2f);
        } else if (state == State.WALK && x + PW / 2.0 >= CASTLE_X + 80) {
            state = State.WIN; winTimer = 0;
            if (level < 5) unlockedLevels[level + 1] = true;
            save();
        }
    }

    void updateFlag() {
        tick++;
        if (y + PH < GROUND_Y) y += 3;
        flagY = Math.min(GROUND_Y - 30, flagY + 3);
        if (y + PH >= GROUND_Y && flagY >= GROUND_Y - 30) {
            y = GROUND_Y - PH;
            if (++winTimer > 25) { x = FLAG_X + 8; vx = vy = 0; jumpBuf = 0; onGround = true; state = State.WALK; }
        }
    }

    void updateWin() {
        winTimer++;
        if (winTimer % 14 == 1)
            firework((float) (camX + 100 + rnd.nextInt(600)), 30 + rnd.nextInt(170), FW_COLORS[rnd.nextInt(FW_COLORS.length)]);
    }

    void firework(float cx, float cy, int rgb) {
        for (int i = 0; i < 42; i++) {
            double a = i * Math.PI * 2 / 42, sp = 2 + rnd.nextFloat() * 3.5;
            spawn(cx, cy, (float) (Math.cos(a) * sp), (float) (Math.sin(a) * sp), 50, 3.5f, rgb);
        }
    }

    static boolean hit(double px, double py, Plat p) {
        return px < p.x + p.w && px + PW > p.x && py < p.y + p.h && py + PH > p.y;
    }

    void update() {
        if (screen != Screen.GAME) { menuTick(); return; }
        if (state == State.DEAD) return;
        if (state == State.DYING) { updateDying(); return; }
        if (state == State.FLAG)  { updateFlag();  return; }
        if (state == State.WIN)   { updateWin();   return; }
        tick++;

        if (speedTimer > 0) speedTimer--;
        if (immunityTimer > 0) immunityTimer--;
        if (jumpTimer > 0) jumpTimer--;

        if (immunityTimer > 0 && tick % 2 == 0) {
            spawn((float)(x + PW/2 + (rnd.nextFloat()-0.5)*20), (float)(y + PH/2 + (rnd.nextFloat()-0.5)*20),
                    0, -0.5f, 15, 3f, 0x50E0FF);
        }

        if (skin == 4 && tick % 2 == 0) {
            spawn((float)(x + PW/2 + (rnd.nextFloat()-0.5)*14), (float)(y + PH - 8),
                    (rnd.nextFloat()-0.5f)*1.2f, -rnd.nextFloat()*1.8f - 0.5f,
                    25, 3.5f, rnd.nextBoolean() ? 0xFF007F : 0xFF66CC);
        }

        double curSpeed = SPEED * (speedTimer > 0 ? 2.0 : 1.0);
        double curJump = JUMP * (jumpTimer > 0 ? 1.45 : 1.0);

        double target = state == State.WALK ? 2.5 : ((right ? 1 : 0) - (left ? 1 : 0)) * curSpeed;
        vx += (target - vx) * (onGround ? 0.35 : 0.2);
        if (target == 0 && Math.abs(vx) < 0.05) vx = 0;
        if (vx > 0.1) facing = 1; else if (vx < -0.1) facing = -1;
        animT += Math.abs(vx) * 0.07;

        x += vx;
        if (x < leftLimit) { x = leftLimit; vx = 0; }
        for (Plat p : platforms) {
            if (p.x > x + PW) break;
            if (hit(x, y, p)) {
                if (vx > 0) x = p.x - PW; else if (vx < 0) x = p.x + p.w;
                vx = 0;
            }
        }

        coyote = onGround ? COYOTE : Math.max(0, coyote - 1);
        jumpBuf = Math.max(0, jumpBuf - 1);
        if (jumpBuf > 0 && coyote > 0) {
            vy = curJump; jumpBuf = 0; coyote = 0; onGround = false; squash = 0.25;
            burst((float) (x + PW / 2), (float) (y + PH), 8, 0xE8DCC0, 2f);
        }
        vy += GRAVITY * ((vy < 0 && !jumpKey) ? 2.4 : 1);
        if (vy > MAX_FALL) vy = MAX_FALL;

        boolean was = onGround;
        y += vy;
        onGround = false;
        for (Plat p : platforms) {
            if (p.x > x + PW) break;
            if (hit(x, y, p)) {
                if (vy > 0) {
                    if (!was && vy > 5) {
                        squash = -Math.min(0.3, vy * 0.025);
                        burst((float) (x + PW / 2), (float) (p.y), 10, 0xD8C8A0, 2.5f);
                        if (vy > 11) shake = 4;
                    }
                    y = p.y - PH; onGround = true;
                } else if (vy < 0) y = p.y + p.h;
                vy = 0;
            }
        }
        squash *= 0.85; shake *= 0.85;

        if (onGround && Math.abs(vx) > 3 && tick % 5 == 0)
            spawn((float) (x + PW / 2 - facing * 8), (float) (y + PH - 2),
                    -facing * 0.6f, -0.4f, 18, 3f, 0xDCCFA8);

        double cx = x + PW / 2.0, cy = y + PH / 2.0;
        for (Coin c : coins) {
            if (!c.got && Math.abs(c.x - cx) < 20 && Math.abs(c.y - cy) < 26) {
                c.got = true; coinCount++; totalCoins++; score += 50;
                pop(c.x, c.y - 10, "+50", 0xFFD728);
                burst((float) c.x, (float) c.y, 10, 0xFFD728, 2.2f);
            }
        }

        for (PowerUp pw : powerUps) {
            if (!pw.got && Math.abs(pw.x - cx) < 24 && Math.abs(pw.y - cy) < 28) {
                pw.got = true;
                applyPowerUp(pw.type);
                burst((float) pw.x, (float) pw.y, 15, 0xFFFFFF, 3f);
            }
        }

        if (x - camX > W * 0.4) camX = x - W * 0.4;
        if (x - camX < W * 0.25) camX = x - W * 0.25;
        camX = Math.max(camX, leftLimit);
        generate();
        updateEnemies();
        checkEnd();

        maxX = Math.max(maxX, x);
        maxDistance = (int) (maxX / 10);
        leftLimit = (int) Math.max(0, maxX - BACK_LIMIT);
        if (y > H + 50) { state = State.DEAD; save(); }
    }

    void applyPowerUp(PowerType type) {
        switch (type) {
            case SPEED_BOOST -> { speedTimer = 600; pop(x, y - 20, "¡VELOCIDAD x2!", 0xFFD728); }
            case IMMUNITY -> { immunityTimer = 1200; pop(x, y - 20, "¡INMUNIDAD (20s)!", 0x50E0FF); }
            case JUMP_BOOST -> { jumpTimer = 600; pop(x, y - 20, "¡SALTO x2!", 0xFF7040); }
        }
    }

    // ============================================================
    // PARTÍCULAS
    // ============================================================
    void spawn(float px, float py, float vx, float vy, int life, float size, int rgb) {
        if (pCount >= MAXP) return;
        int i = pCount++;
        pX[i] = px; pY[i] = py; pVX[i] = vx; pVY[i] = vy;
        pLife[i] = pMax[i] = life; pSize[i] = size; pRGB[i] = rgb;
    }

    void burst(float px, float py, int n, int rgb, float power) {
        for (int i = 0; i < n; i++)
            spawn(px, py, (rnd.nextFloat() - 0.5f) * power * 2, -rnd.nextFloat() * power,
                    20 + rnd.nextInt(15), 2 + rnd.nextFloat() * 3, rgb);
    }

    void pop(double px, double py, String txt, int rgb) { pops.add(new FloatText(px, py, txt, rgb)); }

    void updateParticles() {
        fade = Math.max(0, fade - 0.04);
        if (msgT > 0) msgT--;
        for (FloatText f : pops) { f.y -= 0.7; f.life--; }
        pops.removeIf(f -> f.life <= 0);
        for (int i = pCount - 1; i >= 0; i--) {
            pX[i] += pVX[i]; pY[i] += pVY[i]; pVY[i] += 0.10f; pVX[i] *= 0.96f;
            if (--pLife[i] <= 0) {
                int l = --pCount;
                pX[i] = pX[l]; pY[i] = pY[l]; pVX[i] = pVX[l]; pVY[i] = pVY[l];
                pLife[i] = pLife[l]; pMax[i] = pMax[l]; pSize[i] = pSize[l]; pRGB[i] = pRGB[l];
            }
        }
    }

    // ============================================================
    // PRE-RENDERIZADO DE SPRITES
    // ============================================================
    static BufferedImage img(int w, int h) {
        return GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice()
                .getDefaultConfiguration().createCompatibleImage(w, h, Transparency.TRANSLUCENT);
    }

    static Graphics2D aa(BufferedImage im) {
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        return g;
    }

    void buildSprites(int lvl) {
        themeBuilt = lvl;
        boolean isNight = (lvl % 2 != 0) || lvl == 5;

        sky = img(W, H);
        Graphics2D g = aa(sky);
        Color topSky = lvl == 5 ? new Color(25, 5, 20) : (isNight ? new Color(15, 15, 45) : new Color(60, 120, 225));
        Color botSky = lvl == 5 ? new Color(65, 10, 40) : (isNight ? new Color(40, 30, 80) : new Color(205, 232, 255));
        g.setPaint(new GradientPaint(0, 0, topSky, 0, GROUND_Y, botSky));
        g.fillRect(0, 0, W, H);

        if (!isNight) {
            g.setPaint(new RadialGradientPaint(new Point2D.Float(620, 100), 150, new float[]{0f, 0.3f, 1f},
                    new Color[]{new Color(255, 250, 210, 255), new Color(255, 235, 150, 120), new Color(255, 220, 120, 0)}));
            g.fillRect(0, 0, W, H);
            g.setColor(new Color(255, 252, 228));
            g.fillOval(586, 66, 68, 68);
        }
        g.dispose();

        clouds = img(1600, H);
        g = aa(clouds);
        for (int i = 0; i < 6; i++) cloud(g, i * 250 + 20, 35 + (i * 53) % 110, 0.8 + (i % 3) * 0.3);
        g.dispose();

        far = mountains(250, 90, new Color(125, 155, 215), new Color(180, 208, 240), 2, 5, 0.4);
        mid = mountains(290, 70, new Color(95, 135, 175), new Color(150, 188, 195), 3, 7, 1.7);
        near = mountains(335, 45, new Color(60, 125, 90), new Color(95, 165, 105), 4, 9, 2.9);

        dirt = img(64, 80);
        g = aa(dirt);
        g.setPaint(new GradientPaint(0, 0, new Color(150, 98, 48), 0, 80, new Color(85, 52, 26)));
        g.fillRect(0, 0, 64, 80);
        Random r = new Random(7);
        for (int i = 0; i < 60; i++) {
            g.setColor(r.nextBoolean() ? new Color(0, 0, 0, 35) : new Color(255, 220, 160, 30));
            g.fillRect(r.nextInt(62), r.nextInt(78), 2 + r.nextInt(3), 2 + r.nextInt(2));
        }
        g.dispose();

        if (vignette == null) {
            vignette = img(W, H);
            Graphics2D vg = vignette.createGraphics();
            vg.setPaint(new RadialGradientPaint(new Point2D.Float(W / 2f, H / 2f), W * 0.68f,
                    new float[]{0f, 0.6f, 1f},
                    new Color[]{new Color(0, 0, 0, 0), new Color(0, 0, 0, 0), new Color(0, 0, 0, 120)}));
            vg.fillRect(0, 0, W, H);
            vg.dispose();
        }

        if (isNight) {
            tint(clouds, new Color(20, 20, 70), 0.65f);
            tint(far, new Color(10, 10, 50), 0.6f);     tint(mid, new Color(10, 10, 50), 0.55f);
            tint(near, new Color(10, 20, 60), 0.5f);    tint(dirt, new Color(30, 10, 60), 0.45f);
            Graphics2D sg = aa(sky);
            Random r2 = new Random(3);
            for (int i = 0; i < 90; i++) {
                sg.setColor(new Color(255, 255, 255, 100 + r2.nextInt(155)));
                int sz = r2.nextInt(6) == 0 ? 3 : 2;
                sg.fillRect(r2.nextInt(W), r2.nextInt(GROUND_Y - 60), sz, sz);
            }
            sg.setColor(new Color(240, 244, 255)); sg.fillOval(585, 65, 70, 70);
            sg.dispose();
        }

        trees = Gfx.trees(lvl, isNight);
    }

    static void cloud(Graphics2D g, int x, int y, double s) {
        g.setColor(new Color(0, 0, 60, 22));
        g.fill(new Ellipse2D.Double(x + 6 * s, y + 26 * s, 110 * s, 16 * s));
        g.setColor(new Color(255, 255, 255, 235));
        g.fill(new Ellipse2D.Double(x, y + 10 * s, 70 * s, 30 * s));
        g.fill(new Ellipse2D.Double(x + 30 * s, y, 60 * s, 40 * s));
        g.fill(new Ellipse2D.Double(x + 60 * s, y + 12 * s, 60 * s, 28 * s));
    }

    static BufferedImage mountains(int base, int amp, Color top, Color bottom, int k1, int k2, double ph) {
        BufferedImage im = img(TILE, H);
        Graphics2D g = aa(im);
        Path2D.Double path = new Path2D.Double();
        path.moveTo(0, H);
        for (int i = 0; i <= TILE; i += 4) {
            double t = 2 * Math.PI * i / TILE;
            path.lineTo(i, base - amp * (0.6 * Math.sin(k1 * t + ph) + 0.4 * Math.sin(k2 * t + ph * 2)));
        }
        path.lineTo(TILE, H); path.closePath();
        g.setPaint(new GradientPaint(0, base - amp, top, 0, H, bottom));
        g.fill(path);
        g.dispose();
        return im;
    }

    // ============================================================
    // DIBUJO
    // ============================================================
    void layer(Graphics2D g, BufferedImage im, double factor, int tile) {
        int off = (int) (camX * factor + (im == clouds ? tick * 0.15 : 0)) % tile;
        g.drawImage(im, -off, 0, null);
        if (tile - off < W) g.drawImage(im, tile - off, 0, null);
    }

    void background(Graphics2D g) {
        g.drawImage(sky, 0, 0, null);
        layer(g, clouds, 0.12, 1600);
        layer(g, far, 0.10, TILE);
        layer(g, mid, 0.25, TILE);
        layer(g, near, 0.45, TILE);
        layer(g, trees, 0.62, TILE);
    }

    @Override protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (screen != Screen.GAME) { paintMenu(g); Toolkit.getDefaultToolkit().sync(); return; }
        int cam = (int) camX;

        background(g);

        AffineTransform base = g.getTransform();
        if (shake > 0.3) g.translate((rnd.nextDouble() - 0.5) * shake * 2, (rnd.nextDouble() - 0.5) * shake * 2);

        for (Plat p : platforms) {
            if (p.x - cam < W && p.x + p.w - cam > 0) drawPlat(g, p, cam);
        }

        double t = tick * 0.08;
        drawFinale(g, cam);
        for (Coin c : coins) if (!c.got) drawCoin(g, c, cam, t);
        for (PowerUp pw : powerUps) if (!pw.got) drawPowerUp(g, pw, cam);
        for (Enemy en : enemies) drawEnemy(g, en, cam);
        drawBackWall(g, cam);

        Gfx.particles(g, this, cam);

        drawAmbient(g, cam);
        if (state != State.WIN && state != State.DEAD) drawPlayer(g, (int) x - cam, (int) y);
        g.setFont(F_POP);
        for (FloatText f : pops) {
            g.setColor(new Color((Math.min(255, f.life * 8) << 24) | f.rgb, true));
            g.drawString(f.s, (int) f.x - cam, (int) f.y);
        }
        g.setTransform(base);

        Gfx.post(g, this);
        g.drawImage(vignette, 0, 0, null);

        // HUD
        g.setFont(F_HUD);
        text(g, "Distancia: " + Math.max(0, (int) (x / 10)) + " m / 2000 m", 15, 28);
        text(g, "Nivel " + (level + 1) + (level == 5 ? " [IMPOSIBLE]" : ""), 350, 28);
        g.setColor(GOLD); g.fillOval(15, 38, 16, 16);
        g.setColor(GOLD_DARK); g.drawOval(15, 38, 16, 16);
        text(g, "x " + coinCount + "  (total " + totalCoins + ")", 38, 52);
        text(g, "Puntos: " + score, 15, 76);

        drawPowerUpHUD(g);

        int bw = 200, bx = W - bw - 20;
        g.setColor(new Color(0, 0, 0, 120)); g.fillRoundRect(bx, 18, bw, 10, 10, 10);
        g.setColor(GOLD); g.fillRoundRect(bx, 18, (int) (bw * Math.min(1.0, maxX / FLAG_X)), 10, 10, 10);
        g.setColor(Color.WHITE); g.drawRoundRect(bx, 18, bw, 10, 10, 10);

        if (state == State.DEAD || (state == State.WIN && winTimer > 150)) {
            g.setColor(new Color(0, 0, 0, state == State.WIN ? 90 : 150));
            g.fillRect(0, 0, W, H);
            g.setFont(F_BIG);
            String s1 = state == State.WIN ? "¡NIVEL COMPLETADO!" : "GAME OVER";
            text(g, s1, (W - g.getFontMetrics().stringWidth(s1)) / 2, H / 2 - 10);
            g.setFont(F_SMALL);
            String s2 = "Puntos: " + score + "  |  Monedas: " + coinCount + "  -  R reiniciar | ESC menú";
            text(g, s2, (W - g.getFontMetrics().stringWidth(s2)) / 2, H / 2 + 28);
            if (state == State.WIN && level < 5) text(g, "¡Siguiente nivel desbloqueado!", W / 2 - 140, H / 2 + 62);
        }
        drawFade(g);
        Toolkit.getDefaultToolkit().sync();
    }

    void drawPowerUpHUD(Graphics2D g) {
        int py = 45;
        g.setFont(F_POP);
        if (immunityTimer > 0) {
            g.setColor(new Color(80, 224, 255));
            g.drawString("Inmunidad: " + (immunityTimer / 60) + "s", W - 180, py);
            py += 20;
        }
        if (speedTimer > 0) {
            g.setColor(new Color(255, 215, 40));
            g.drawString("Velocidad x2: " + (speedTimer / 60) + "s", W - 180, py);
            py += 20;
        }
        if (jumpTimer > 0) {
            g.setColor(new Color(255, 112, 64));
            g.drawString("Salto x2: " + (jumpTimer / 60) + "s", W - 180, py);
        }
    }

    // --- Delegados al motor gráfico ---
    void drawPlat(Graphics2D g, Plat p, int cam)            { Gfx.plat(g, this, p, cam); }
    void drawCoin(Graphics2D g, Coin c, int cam, double t)  { Gfx.coin(g, this, c, cam, t); }
    void drawPowerUp(Graphics2D g, PowerUp pw, int cam)     { Gfx.power(g, this, pw, cam); }
    void drawEnemy(Graphics2D g, Enemy en, int cam)         { Gfx.enemy(g, this, en, cam); }
    void drawPlayer(Graphics2D g, int sx, int sy)           { Gfx.player(g, this, sx, sy); }

    void drawBackWall(Graphics2D g, int cam) {
        double d = x - leftLimit;
        if (d > 200 || state != State.PLAY) return;
        int a = (int) (150 * (1 - d / 200));
        int wx = leftLimit - cam;
        g.setPaint(new GradientPaint(wx, 0, new Color(255, 70, 70, a), wx + 40, 0, new Color(255, 70, 70, 0)));
        g.fillRect(wx, 0, 40, H);
    }

    void drawFinale(Graphics2D g, int cam) {
        if (FLAG_X - 60 - cam > W || CASTLE_X + 170 - cam < 0) return;
        int fx = FLAG_X - cam;
        g.setColor(new Color(235, 235, 235)); g.fillRect(fx - 2, GROUND_Y - 300, 4, 300);
        g.setColor(new Color(60, 200, 80));   g.fillOval(fx - 6, GROUND_Y - 308, 12, 12);

        int fy = (int) flagY;
        g.setColor(new Color(60, 200, 80));
        g.fillPolygon(new int[]{fx - 2, fx - 42, fx - 2}, new int[]{fy, fy + 14, fy + 28}, 3);
        g.setColor(Color.WHITE); g.fillOval(fx - 20, fy + 9, 8, 8);

        int cx = CASTLE_X - cam;
        g.setPaint(new GradientPaint(0, GROUND_Y - 110, new Color(185, 95, 55), 0, GROUND_Y, new Color(140, 65, 35)));
        g.fillRect(cx, GROUND_Y - 110, 160, 110);
        g.fillRect(cx + 50, GROUND_Y - 170, 60, 60);
        g.setColor(new Color(0, 0, 0, 50));
        for (int i = 0; i < 170; i += 14) g.drawLine(cx + (i < 110 ? 0 : 50), GROUND_Y - i, cx + (i < 110 ? 160 : 110), GROUND_Y - i);
        g.setColor(new Color(150, 72, 42));
        for (int i = 0; i < 5; i++) g.fillRect(cx + i * 36, GROUND_Y - 128, 24, 18);
        for (int i = 0; i < 3; i++) g.fillRect(cx + 50 + i * 24, GROUND_Y - 188, 14, 18);
        g.setColor(new Color(30, 15, 10));
        g.fillRect(cx + 65, GROUND_Y - 50, 30, 50); g.fillArc(cx + 65, GROUND_Y - 65, 30, 30, 0, 180);
        g.fillRect(cx + 18, GROUND_Y - 85, 16, 26); g.fillRect(cx + 126, GROUND_Y - 85, 16, 26);
        g.setColor(Color.WHITE); g.fillRect(cx + 79, GROUND_Y - 225, 2, 40);
        g.setColor(new Color(215, 30, 30)); g.fillPolygon(new int[]{cx + 81, cx + 103, cx + 81}, new int[]{GROUND_Y - 225, GROUND_Y - 216, GROUND_Y - 207}, 3);
    }

    // ============================================================
    // GUARDADO Y SKINS
    // ============================================================
    void load() {
        try {
            totalCoins = prefs.getInt("coins", 0);
            skin = prefs.getInt("skin", 0);
            for (int i = 1; i < owned.length; i++) owned[i] = prefs.getBoolean("owned" + i, false);
            for (int i = 1; i < unlockedLevels.length; i++) unlockedLevels[i] = prefs.getBoolean("lvl" + i, false);
            if (skin < 0 || skin >= owned.length || !owned[skin]) skin = 0;
        } catch (Exception e) { }
    }

    void save() {
        try {
            prefs.putInt("coins", totalCoins); prefs.putInt("skin", skin);
            for (int i = 1; i < owned.length; i++) prefs.putBoolean("owned" + i, owned[i]);
            for (int i = 1; i < unlockedLevels.length; i++) prefs.putBoolean("lvl" + i, unlockedLevels[i]);
            prefs.flush();
        } catch (Exception e) { }
    }

    static Color shade(Color c, int d) {
        return new Color(Math.max(0, Math.min(255, c.getRed() + d)), Math.max(0, Math.min(255, c.getGreen() + d)),
                Math.max(0, Math.min(255, c.getBlue() + d)));
    }

    static void applySkin(int i) {
        switch (i) {
            case 1 -> { RED = new Color(50, 110, 235); DARK_RED = new Color(30, 70, 170); BLUE = new Color(238, 240, 250); }
            case 2 -> { RED = new Color(40, 180, 70); DARK_RED = new Color(20, 120, 40); BLUE = new Color(30, 50, 30); }
            case 3 -> { RED = new Color(150, 40, 190); DARK_RED = new Color(100, 20, 130); BLUE = new Color(240, 220, 60); }
            case 4 -> { RED = new Color(20, 20, 25); DARK_RED = new Color(10, 10, 15); BLUE = new Color(40, 40, 45); }
            default -> { RED = new Color(215, 30, 30); DARK_RED = new Color(160, 20, 20); BLUE = new Color(40, 70, 210); }
        }
        RED_HI = shade(RED, 25); BLUE_HI = shade(BLUE, 25); BLUE_LO = shade(BLUE, -45);
    }

    static void tint(BufferedImage im, Color c, float a) {
        Graphics2D g = im.createGraphics();
        g.setComposite(AlphaComposite.SrcAtop.derive(a));
        g.setColor(c);
        g.fillRect(0, 0, im.getWidth(), im.getHeight());
        g.dispose();
    }

    void flash(String m) { msg = m; msgT = 120; }

    void startLevel(int l) {
        level = l;
        ENEMY_MUL = 1.0 + (l * 0.25) + (l == 5 ? 0.35 : 0);
        buildSprites(l); reset();
        left = right = jumpKey = false;
        screen = Screen.GAME; fade = 1;
    }

    void exitToMenu() {
        save();
        if (themeBuilt != 0) buildSprites(0);
        reset();
        left = right = jumpKey = false;
        screen = Screen.MENU; fade = 1;
    }

    void menuTick() {
        tick++; camX += 1.5; animT += 0.28;
        vx = 4; vy = 0; onGround = true; facing = 1; squash = 0;
    }

    void buy(int i) {
        if (owned[i]) flash("Ya tienes esta skin");
        else if (totalCoins < SKIN_PRICE[i]) flash("Te faltan " + (SKIN_PRICE[i] - totalCoins) + " monedas");
        else {
            totalCoins -= SKIN_PRICE[i]; owned[i] = true; skin = i; applySkin(i); save();
            flash("¡Comprada y equipada!");
        }
    }

    void menuKey(int k) {
        boolean up = k == KeyEvent.VK_UP || k == KeyEvent.VK_W, down = k == KeyEvent.VK_DOWN || k == KeyEvent.VK_S,
                lf = k == KeyEvent.VK_LEFT || k == KeyEvent.VK_A, rt = k == KeyEvent.VK_RIGHT || k == KeyEvent.VK_D,
                ok = k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE, back = k == KeyEvent.VK_ESCAPE;
        int nSkins = owned.length;

        switch (screen) {
            case MENU -> {
                if (up) sel = (sel + MENU_ITEMS.length - 1) % MENU_ITEMS.length;
                if (down) sel = (sel + 1) % MENU_ITEMS.length;
                if (ok) {
                    switch (sel) {
                        case 0 -> screen = Screen.LEVEL_SELECT;
                        case 1 -> screen = Screen.SHOP;
                        case 2 -> { screen = Screen.SKINS; skinSel = skin; }
                        default -> { save(); System.exit(0); }
                    }
                }
            }
            case LEVEL_SELECT -> {
                if (up || lf) levelSel = (levelSel + 5) % 6;
                if (down || rt) levelSel = (levelSel + 1) % 6;
                if (ok) {
                    if (unlockedLevels[levelSel]) startLevel(levelSel);
                    else flash("Nivel bloqueado: supera los anteriores");
                }
                if (back) screen = Screen.MENU;
            }
            case SHOP -> {
                if (lf || up) shopSel = shopSel <= 1 ? nSkins - 1 : shopSel - 1;
                if (rt || down) shopSel = shopSel >= nSkins - 1 ? 1 : shopSel + 1;
                if (ok) buy(shopSel);
                if (back) screen = Screen.MENU;
            }
            case SKINS -> {
                if (lf) skinSel = (skinSel + nSkins - 1) % nSkins;
                if (rt) skinSel = (skinSel + 1) % nSkins;
                if (ok) {
                    if (owned[skinSel]) { skin = skinSel; applySkin(skin); save(); flash("Skin equipada"); }
                    else flash("Bloqueada: cómprala en la tienda");
                }
                if (back) screen = Screen.MENU;
            }
            default -> { }
        }
    }

    // ============================================================
    // MENÚS
    // ============================================================
    void drawFade(Graphics2D g) {
        if (fade > 0.01) { g.setColor(new Color(0, 0, 0, (int) (255 * Math.min(1, fade)))); g.fillRect(0, 0, W, H); }
    }

    void drawAmbient(Graphics2D g, int cam) {
        boolean night = (themeBuilt % 2 != 0) || themeBuilt == 5;
        for (int i = 0; i < 24; i++) {
            double px = (((i * 137 - cam * (0.5 + (i % 3) * 0.15) + tick * 0.15 * (1 + i % 2)) % W) + W) % W;
            double py = 70 + (i * 53) % 230 + Math.sin(tick * 0.03 + i) * 12;
            if (night) {
                g.setColor(FLY_GLOW); g.fill(new Ellipse2D.Double(px - 6, py - 6, 12, 12));
                g.setColor(FLY_CORE); g.fill(new Ellipse2D.Double(px - 1.5, py - 1.5, 3, 3));
            } else { g.setColor(POLLEN); g.fill(new Ellipse2D.Double(px - 1.5, py - 1.5, 3, 3)); }
        }
    }

    static void centered(Graphics2D g, String s, int cx, int y) {
        text(g, s, cx - g.getFontMetrics().stringWidth(s) / 2, y);
    }

    static void panel(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(new Color(10, 15, 40, 190)); g.fillRoundRect(x, y, w, h, 24, 24);
        g.setColor(new Color(255, 255, 255, 70)); g.drawRoundRect(x, y, w, h, 24, 24);
    }

    void button(Graphics2D g, String s, int x, int y, int w, int h, boolean sel, boolean on) {
        if (sel) {
            g.setColor(new Color(255, 215, 60, 70)); g.fillRoundRect(x - 6, y - 6, w + 12, h + 12, 26, 26);
            g.setPaint(new GradientPaint(0, y, new Color(255, 225, 90), 0, y + h, new Color(240, 150, 30)));
        } else g.setColor(new Color(10, 15, 40, on ? 170 : 110));
        g.fillRoundRect(x, y, w, h, 20, 20);
        g.setColor(new Color(255, 255, 255, sel ? 200 : 60)); g.drawRoundRect(x, y, w, h, 20, 20);
        g.setFont(F_MENU);
        g.setColor(sel ? new Color(70, 30, 0) : (on ? Color.WHITE : new Color(150, 150, 160)));
        g.drawString(s, x + (w - g.getFontMetrics().stringWidth(s)) / 2, y + h / 2 + 7);
    }

    void coinBalance(Graphics2D g) {
        g.setFont(F_HUD);
        String s = String.valueOf(totalCoins);
        int w = g.getFontMetrics().stringWidth(s);
        g.setColor(new Color(0, 0, 0, 110)); g.fillRoundRect(W - w - 66, 12, w + 52, 32, 16, 16);
        g.setColor(GOLD_DARK); g.fillOval(W - w - 56, 18, 20, 20);
        g.setColor(GOLD); g.fillOval(W - w - 54, 20, 16, 16);
        text(g, s, W - w - 28, 35);
    }

    void previewMario(Graphics2D g, int cx, int fy, double sc) {
        AffineTransform old = g.getTransform();
        g.translate(cx, fy); g.scale(sc, sc); g.translate(0, -GROUND_Y);
        drawPlayer(g, -PW / 2, GROUND_Y - PH);
        g.setTransform(old);
    }

    void paintMenu(Graphics2D g) {
        int cam = (int) camX;
        background(g);
        drawPlat(g, new Plat(cam - 64, GROUND_Y, W + 128, H - GROUND_Y), cam);
        drawAmbient(g, cam);
        Gfx.post(g, this);
        g.drawImage(vignette, 0, 0, null);
        switch (screen) {
            case MENU -> drawMainMenu(g);
            case LEVEL_SELECT -> drawLevelSelect(g);
            case SHOP -> drawShop(g);
            case SKINS -> drawSkins(g);
            default -> { }
        }
        if (msgT > 0) {
            g.setFont(F_HUD);
            int w = g.getFontMetrics().stringWidth(msg) + 40;
            g.setColor(new Color(0, 0, 0, Math.min(190, msgT * 6))); g.fillRoundRect((W - w) / 2, H - 70, w, 34, 16, 16);
            g.setColor(new Color(255, 255, 255, Math.min(255, msgT * 8))); g.drawString(msg, (W - w) / 2 + 20, H - 48);
        }
        drawFade(g);
    }

    void drawMainMenu(Graphics2D g) {
        previewMario(g, 170, GROUND_Y, 2.2);
        g.setFont(F_TITLE);
        String t = "MINI MARIO";
        int ty = 92 + (int) (Math.sin(tick * 0.05) * 4);
        int tx = (W - g.getFontMetrics().stringWidth(t)) / 2;
        g.setColor(new Color(90, 15, 15)); g.drawString(t, tx + 4, ty + 4);
        g.setPaint(new GradientPaint(0, ty - 46, new Color(255, 240, 110), 0, ty, new Color(255, 130, 30)));
        g.drawString(t, tx, ty);
        for (int i = 0; i < MENU_ITEMS.length; i++) {
            button(g, MENU_ITEMS[i], 330, 140 + i * 48, 300, 38, i == sel, true);
        }
        coinBalance(g);
        g.setFont(F_POP);
        text(g, "W/S o flechas: mover  |  ENTER: elegir", 20, H - 14);
    }

    void drawLevelSelect(Graphics2D g) {
        panel(g, 80, 25, 640, 370);
        g.setFont(F_BIG); centered(g, "SELECCIONAR NIVEL", W / 2, 70);
        for (int i = 0; i < 6; i++) {
            boolean unlocked = unlockedLevels[i];
            String name = "Nivel " + (i + 1) + (i == 5 ? " (IMPOSIBLE)" : "");
            if (!unlocked) name += " [Bloqueado]";
            button(g, name, 200, 92 + i * 42, 400, 34, i == levelSel, unlocked);
        }
        g.setFont(F_POP); centered(g, "ENTER: Jugar  |  ESC: Volver", W / 2, 372);
        coinBalance(g);
    }

    void drawShop(Graphics2D g) {
        panel(g, 110, 40, 580, 300);
        g.setFont(F_BIG); centered(g, "TIENDA", W / 2, 95);
        int i = shopSel;
        boolean have = owned[i], can = !have && totalCoins >= SKIN_PRICE[i];
        applySkin(i); previewMario(g, 245, 300, 3.2); applySkin(skin);
        g.setFont(F_MENU); text(g, SKIN_NAMES[i], 380, 150);
        g.setFont(F_SMALL); text(g, "Precio: " + SKIN_PRICE[i] + " monedas", 380, 185);
        button(g, have ? "YA LA TIENES" : can ? "ENTER: comprar" : "Te faltan " + (SKIN_PRICE[i] - totalCoins), 380, 205, 260, 38, can, have || can);
        g.setFont(F_POP); text(g, "ESC: volver", 380, 280);
        coinBalance(g);
    }

    void drawSkins(Graphics2D g) {
        panel(g, 40, 40, 720, 300);
        g.setFont(F_BIG); centered(g, "SKINS", W / 2, 95);
        int n = SKIN_NAMES.length;
        for (int i = 0; i < n; i++) {
            int cx = 100 + i * 140;
            if (i == skinSel) {
                g.setColor(new Color(255, 215, 60, 60)); g.fillRoundRect(cx - 55, 110, 110, 190, 22, 22);
                g.setColor(GOLD); g.drawRoundRect(cx - 55, 110, 110, 190, 22, 22);
            }
            applySkin(i); previewMario(g, cx, 240, 2.0);
            g.setFont(F_SMALL);
            centered(g, SKIN_NAMES[i], cx, 265);
            g.setFont(F_POP);
            centered(g, i == skin ? "EQUIPADA" : owned[i] ? "ENTER" : "BLOQUEADA", cx, 288);
        }
        applySkin(skin);
        g.setFont(F_POP); centered(g, "A/D: elegir  |  ENTER: equipar  |  ESC: volver", W / 2, 328);
        coinBalance(g);
    }

    static void text(Graphics2D g, String s, int x, int y) {
        g.setColor(new Color(0, 0, 0, 140));
        g.drawString(s, x + 2, y + 2);
        g.setColor(Color.WHITE);
        g.drawString(s, x, y);
    }

    double groundBelow() {
        double best = H;
        for (Plat p : platforms) {
            if (p.x > x + PW) break;
            if (p.y >= y + PH - 1 && x + PW > p.x && x < p.x + p.w && p.y < best) best = p.y;
        }
        return best;
    }

    // ============================================================
    // TECLADO
    // ============================================================
    @Override public void keyPressed(KeyEvent e) {
        if (screen != Screen.GAME) { menuKey(e.getKeyCode()); return; }
        switch (e.getKeyCode()) {
            case KeyEvent.VK_LEFT, KeyEvent.VK_A -> left = true;
            case KeyEvent.VK_RIGHT, KeyEvent.VK_D -> right = true;
            case KeyEvent.VK_UP, KeyEvent.VK_W, KeyEvent.VK_SPACE -> {
                if (!jumpKey && state == State.PLAY) jumpBuf = JUMP_BUFFER;
                jumpKey = true;
            }
            case KeyEvent.VK_R -> { if (state == State.DEAD || state == State.WIN) reset(); }
            case KeyEvent.VK_ESCAPE -> exitToMenu();
        }
    }

    @Override public void keyReleased(KeyEvent e) {
        switch (e.getKeyCode()) {
            case KeyEvent.VK_LEFT, KeyEvent.VK_A -> left = false;
            case KeyEvent.VK_RIGHT, KeyEvent.VK_D -> right = false;
            case KeyEvent.VK_UP, KeyEvent.VK_W, KeyEvent.VK_SPACE -> jumpKey = false;
        }
    }

    @Override public void keyTyped(KeyEvent e) {}

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame f = new JFrame("Mini Mario");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            Mario game = new Mario();
            f.add(game);
            f.pack();
            f.setResizable(false);
            f.setLocationRelativeTo(null);
            f.setVisible(true);
            game.requestFocusInWindow();
        });
    }
}