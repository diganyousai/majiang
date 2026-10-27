package cn.qingzhu;

import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.concurrent.ExecutionException;
import static cn.qingzhu.Game.*;
import cn.qingzhu.Game.Action;
import static cn.qingzhu.Artwork.*;

public final class GameWindow extends JFrame {
    final Game game;
    final Board board = new Board();
    final JButton discard = button("出牌", true), hint = button("提示", false), next = button("下一局", true);
    final JCheckBox auto = checkbox("托管"), pause = checkbox("暂停"), sound = checkbox("音效");
    final JComboBox<String> speed = new JComboBox<>(new String[]{"从容", "标准", "快速"});
    final JPanel actionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 7, 0));
    final JLabel status = new JLabel(" ");
    private final JLabel roundLabel = new JLabel();
    private final JPanel sidebar = new JPanel(new BorderLayout(0, 22));
    private final JTextArea history = new JTextArea();
    private final JLabel[] scores = new JLabel[4];
    private final Preferences preferences = Preferences.userNodeForPackage(GameWindow.class);
    private final Timer timer;
    private SwingWorker<Action, Void> worker;
    private Clip clip;
    private long selectedRevision = -1;
    private int shownResultRound = -1;
    JDialog resultDialog;
    private JDialog reviewDialog;
    boolean smokeMode;

    public GameWindow(long seed) { this(seed, Rules.friends()); }
    public GameWindow(long seed, Rules rules) {
        super("青竹麻将 · 山西扣点点 " + APP_VERSION); game = new Game(seed, rules);
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE); setIconImage(ICON);
        Rectangle screen = getGraphicsConfiguration().getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(getGraphicsConfiguration());
        int width = Math.min(1400, screen.width - insets.left - insets.right);
        int height = Math.min(940, screen.height - insets.top - insets.bottom);
        setMinimumSize(new Dimension(Math.min(1000, width), Math.min(720, height)));
        setSize(width, height); setLocationRelativeTo(null);
        JPanel root = new JPanel(new BorderLayout()); setContentPane(root);
        root.add(header(), BorderLayout.NORTH);
        JPanel play = new JPanel(new BorderLayout()); play.setBackground(TABLE_EDGE);
        play.add(board, BorderLayout.CENTER); play.add(sidebar(), BorderLayout.EAST);
        root.add(play, BorderLayout.CENTER); root.add(footer(), BorderLayout.SOUTH);
        timer = new Timer(650, ignored -> launchBot()); timer.setRepeats(false);
        board.onSelect = i -> refresh(); board.onDiscard = this::discardSelected;
        discard.addActionListener(e -> discardSelected()); hint.addActionListener(e -> showHint());
        next.addActionListener(e -> newRound(false));
        auto.addActionListener(e -> { board.selected = -1; refresh(); });
        pause.addActionListener(e -> { if (pause.isSelected()) timer.stop(); refresh(); });
        sound.setSelected(preferences.getBoolean("sound", true));
        sound.addActionListener(e -> preferences.putBoolean("sound", sound.isSelected()));
        speed.setSelectedIndex(Math.max(0, Math.min(2, preferences.getInt("speed", 1))));
        speed.addActionListener(e -> { preferences.putInt("speed", speed.getSelectedIndex()); timer.stop(); refresh(); });
        bind("ENTER", "discard", this::discardSelected); bind("H", "hint", this::showHint);
        bind("SPACE", "pause", () -> pause.doClick());
        addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                boolean show = getWidth() >= 1260;
                if (sidebar.isVisible() != show) { sidebar.setVisible(show); root.revalidate(); }
            }
        });
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                timer.stop(); if (worker != null) worker.cancel(true); if (clip != null) clip.close();
                if (resultDialog != null) resultDialog.dispose();
                if (reviewDialog != null) reviewDialog.dispose();
            }
        });
        try {
            var resource = GameWindow.class.getResource("/assets/tile.wav");
            try (AudioInputStream stream = AudioSystem.getAudioInputStream(resource)) { clip = AudioSystem.getClip(); clip.open(stream); }
        } catch (Exception ignored) { sound.setSelected(false); sound.setEnabled(false); sound.setToolTipText("未找到可用音频设备"); }
        refresh();
    }

    static JButton button(String text, boolean primary) {
        JButton b = new JButton(text); b.setFont(font(15, true)); b.setFocusPainted(false);
        b.setForeground(INK); b.setBackground(primary ? GOLD : PAPER);
        b.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(0xB9C2CF)), new EmptyBorder(9, 16, 9, 16)));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)); return b;
    }
    static JCheckBox checkbox(String name) {
        JCheckBox box = new JCheckBox(name); box.setOpaque(false); box.setFont(font(14, false)); box.setFocusPainted(false);
        return box;
    }
    private JPanel header() {
        JPanel p = new JPanel(new BorderLayout(18, 0)); p.setBorder(new EmptyBorder(14, 22, 14, 22));
        p.setBackground(NAVY);
        JPanel name = new JPanel(new FlowLayout(FlowLayout.LEFT, 13, 0)); name.setOpaque(false);
        JLabel brand = new JLabel("青竹麻将"); brand.setFont(font(26, true)); brand.setForeground(LIGHT);
        JLabel edition = new JLabel("扣点点"); edition.setFont(font(12, true)); edition.setForeground(GOLD);
        name.add(brand); name.add(edition); p.add(name, BorderLayout.WEST);
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 14, 0)); tools.setOpaque(false);
        sound.setForeground(LIGHT); pause.setForeground(LIGHT); tools.add(sound); tools.add(pause);
        JButton rules = button("规则", false), restart = button("重新开局", false);
        rules.addActionListener(e -> showRules()); restart.addActionListener(e -> restart());
        JButton configure = button("新建牌桌", false);
        configure.addActionListener(e -> {
            boolean wasPaused = pause.isSelected(); pause.setSelected(true); timer.stop(); refresh();
            Rules selected = RulesDialog.choose(this);
            if (selected != null) { GameWindow window = new GameWindow(System.nanoTime(), selected); window.setVisible(true); dispose(); }
            else { pause.setSelected(wasPaused); refresh(); }
        });
        tools.add(configure); tools.add(rules); tools.add(restart); p.add(tools, BorderLayout.EAST); return p;
    }
    private JPanel sidebar() {
        sidebar.setPreferredSize(new Dimension(236, 500)); sidebar.setBackground(NAVY);
        sidebar.setBorder(new EmptyBorder(22, 20, 20, 20));
        JPanel top = new JPanel(); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS)); top.setOpaque(false);
        roundLabel.setFont(font(17, true)); roundLabel.setForeground(GOLD); top.add(roundLabel);
        top.add(Box.createVerticalStrut(24));
        JLabel title = new JLabel("本场积分"); title.setFont(font(13, false)); title.setForeground(MUTED); top.add(title);
        for (int i = 0; i < 4; i++) {
            scores[i] = new JLabel(); scores[i].setFont(font(16, i == 0)); scores[i].setForeground(LIGHT);
            scores[i].setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
            scores[i].setPreferredSize(new Dimension(196, 44));
            scores[i].setBorder(new EmptyBorder(11, 0, 11, 0)); top.add(scores[i]);
        }
        sidebar.add(top, BorderLayout.NORTH);
        JPanel log = new JPanel(new BorderLayout(0, 12)); log.setOpaque(false);
        JLabel logTitle = new JLabel("牌局记录"); logTitle.setFont(font(13, false)); logTitle.setForeground(MUTED);
        history.setEditable(false); history.setFocusable(false); history.setFont(font(13, false)); history.setForeground(LIGHT);
        history.setBackground(sidebar.getBackground()); history.setLineWrap(true); history.setWrapStyleWord(true);
        history.setBorder(null);
        JScrollPane scroll = new JScrollPane(history); scroll.setBorder(null); scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(5, 0));
        log.add(logTitle, BorderLayout.NORTH); log.add(scroll, BorderLayout.CENTER); sidebar.add(log, BorderLayout.CENTER);
        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); options.setOpaque(false);
        JLabel pace = new JLabel("节奏   "); pace.setFont(font(14, false)); pace.setForeground(MUTED);
        speed.setFont(font(14, false)); speed.setPreferredSize(new Dimension(104, 31)); options.add(pace); options.add(speed);
        sidebar.add(options, BorderLayout.SOUTH); return sidebar;
    }
    private JPanel footer() {
        JPanel p = new JPanel(new BorderLayout(12, 0)); p.setBorder(new EmptyBorder(12, 20, 12, 20));
        p.setBackground(PAPER); p.setPreferredSize(new Dimension(100, 106));
        status.setFont(font(14, false)); status.setForeground(INK); p.add(status, BorderLayout.NORTH);
        JPanel bottom = new JPanel(new BorderLayout()); bottom.setOpaque(false); bottom.setBorder(new EmptyBorder(10, 0, 0, 0));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0)); left.setOpaque(false);
        auto.setForeground(INK); left.add(auto); left.add(hint);
        actionPanel.setOpaque(false); bottom.add(left, BorderLayout.WEST); bottom.add(actionPanel, BorderLayout.CENTER);
        p.add(bottom, BorderLayout.CENTER); return p;
    }
    private void bind(String stroke, String name, Runnable action) {
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(stroke), name);
        getRootPane().getActionMap().put(name, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { action.run(); }
        });
    }

    void refresh() {
        View v = game.view(0);
        if (selectedRevision != v.revision()) { board.selected = -1; selectedRevision = v.revision(); }
        board.view = v; board.paused = pause.isSelected(); board.auto = auto.isSelected(); board.repaint();
        roundLabel.setText("第 " + v.round() + " 局  ·  " + v.remaining() + " 张");
        for (int i = 0; i < 4; i++) scores[i].setText(v.players().get(i).name() + "   "
            + (v.players().get(i).score() > 0 ? "+" : "") + v.players().get(i).score() + " 分");
        String log = String.join("\n\n", v.events());
        if (!history.getText().equals(log)) { history.setText(log); history.setCaretPosition(history.getDocument().getLength()); }
        boolean usable = !pause.isSelected() && !auto.isSelected();
        boolean ownTurn = v.phase() == Phase.DISCARD && v.current() == 0;
        hint.setEnabled(usable && ownTurn && worker == null);
        discard.setEnabled(usable && ownTurn && board.selected >= 0 && board.selected < v.self().hand().size()
            && v.actions().contains(new Action(Kind.DISCARD, v.self().hand().get(board.selected))));
        actionPanel.removeAll();
        if (v.phase() == Phase.OVER) {
            JButton review=button("查看全场牌",false); review.addActionListener(e->showReview(v));
            actionPanel.add(review); actionPanel.add(next);
        }
        else {
            for (Kind kind : List.of(Kind.TING, Kind.PONG, Kind.KONG, Kind.CONCEALED_KONG, Kind.ADDED_KONG, Kind.WIN, Kind.PASS)) {
                List<Action> actions = v.actions().stream().filter(a -> a.kind() == kind).toList();
                if (actions.isEmpty()) continue;
                JButton b = button(kind == Kind.WIN && ownTurn ? "自摸" : actions.getFirst().label(), kind == Kind.WIN);
                b.setName(kind.name()); b.setEnabled(usable); b.addActionListener(e -> chooseClaim(b, actions)); actionPanel.add(b);
            }
            actionPanel.add(discard);
        }
        actionPanel.revalidate(); actionPanel.doLayout(); actionPanel.repaint();
        String message;
        if (pause.isSelected()) message = "牌局已暂停";
        else if (v.phase() == Phase.OVER) message = v.result().winner() < 0 ? "本局流局" : v.players().get(v.result().winner()).name() + v.result().method();
        else if (auto.isSelected()) message = "托管中";
        else if (!ownTurn && !v.actions().isEmpty()) message = v.phase() == Phase.ROB_KONG ? "可以抢杠胡" :
            v.players().get(v.lastSource()).name() + "打出" + Tiles.name(v.lastTile()) + "，等待你应牌";
        else if (ownTurn) message = board.selected >= 0 ? "已选：" + Tiles.name(v.self().hand().get(board.selected)) : "轮到你出牌";
        else message = v.events().isEmpty() ? " " : v.events().getLast();
        status.setText(message);
        if (v.phase() == Phase.OVER && shownResultRound != v.round()) { shownResultRound = v.round(); showResult(v); }
        queueBot();
    }
    private void chooseClaim(JButton source, List<Action> actions) {
        if (actions.size() == 1) { perform(0, actions.getFirst()); return; }
        JPopupMenu menu = new JPopupMenu();
        for (Action a : actions) {
            String text = a.kind() == Kind.TING ? "打出" + Tiles.name(a.tile()) + "并报听" : a.sequence().isEmpty() ? Tiles.name(a.tile()) : String.join("  ", a.sequence().stream().map(Tiles::name).toList());
            JMenuItem item = new JMenuItem(text); item.setFont(font(16, false)); item.addActionListener(e -> perform(0, a)); menu.add(item);
        }
        menu.show(source, 0, -menu.getPreferredSize().height);
    }
    void discardSelected() {
        if (pause.isSelected() || auto.isSelected()) return;
        View v = game.view(0); int index = board.selected;
        if (index < 0 || index >= v.self().hand().size()) return;
        Action action = new Action(Kind.DISCARD, v.self().hand().get(index));
        if (v.actions().contains(action)) perform(0, action);
    }
    private void showHint() {
        if (!hint.isEnabled()) return;
        View v = game.view(0); int tile = Bot.hint(v);
        board.selected = v.self().hand().indexOf(tile); refresh(); status.setText("建议打出：" + Tiles.name(tile));
    }
    void perform(int seat, Action action) {
        if (!game.legalActions(seat).contains(action)) return;
        game.act(seat, action); game.checkInvariants();
        if (sound.isSelected() && clip != null && action.kind() != Kind.PASS) {
            clip.stop(); clip.setFramePosition(0); clip.start();
        }
        refresh();
    }
    private int botSeat() {
        if (game.phase == Phase.OVER) return -1;
        if (game.phase == Phase.DISCARD) return game.current != 0 || auto.isSelected() ? game.current : -1;
        for (int seat = 1; seat < 4; seat++) if (!game.legalActions(seat).isEmpty()) return seat;
        return auto.isSelected() && !game.legalActions(0).isEmpty() ? 0 : -1;
    }
    private void queueBot() {
        if (smokeMode || pause.isSelected() || worker != null || timer == null || timer.isRunning() || botSeat() < 0) return;
        timer.setInitialDelay(new int[]{1100, 650, 220}[speed.getSelectedIndex()]); timer.restart();
    }
    private void launchBot() {
        int seat = botSeat(); if (pause.isSelected() || seat < 0 || worker != null) return;
        View snapshot = game.view(seat);
        worker = new SwingWorker<>() {
            @Override protected Action doInBackground() { return Bot.choose(snapshot); }
            @Override protected void done() {
                worker = null;
                if (!isDisplayable()) return;
                try {
                    Action action = get();
                    if (!pause.isSelected() && snapshot.revision() == game.revision && (seat != 0 || auto.isSelected())) perform(seat, action);
                } catch (java.util.concurrent.CancellationException ignored) {
                } catch (InterruptedException e) { Thread.currentThread().interrupt();
                } catch (ExecutionException e) { Main.report(e.getCause()); pause.setSelected(true); }
                refresh();
            }
        };
        worker.execute();
    }
    void newRound(boolean restart) {
        timer.stop(); if (resultDialog != null) { resultDialog.dispose(); resultDialog = null; }
        if (reviewDialog != null) { reviewDialog.dispose(); reviewDialog = null; }
        game.newRound(restart); shownResultRound = -1; board.selected = -1; pause.setSelected(false); refresh();
    }
    private void restart() {
        boolean wasPaused = pause.isSelected(); pause.setSelected(true); timer.stop(); refresh();
        int choice = JOptionPane.showConfirmDialog(this, "重新发牌并开始本局？本局已入账积分会回退，之前各局积分保留。", "重新开局", JOptionPane.OK_CANCEL_OPTION);
        if (choice == JOptionPane.OK_OPTION) newRound(true);
        else { pause.setSelected(wasPaused); refresh(); }
    }
    private void showRules() {
        boolean wasPaused = pause.isSelected(); pause.setSelected(true); timer.stop(); refresh();
        JTextArea rules = new JTextArea(game.rules.description()
            + "\n\n首局你坐庄，谁胡谁庄；荒庄留庄。杠后从牌墙另一端补牌。\n"
            + "风嘴子：不同的三风或中发白组成一组，每组5分，自摸翻倍；风嘴子胡牌仅限自摸。\n"
            + "金币场杠分实时入账；亲友房在局末按房间规则结算。\n"
            + "仅模拟积分，不涉及真钱。此窗口为单机练习；四人联机请在启动菜单中创建或加入房间。尚无牌局存档。");
        rules.setFont(font(15, false)); rules.setEditable(false); rules.setOpaque(false);
        JOptionPane.showMessageDialog(this, rules, "当前牌桌规则", JOptionPane.PLAIN_MESSAGE);
        pause.setSelected(wasPaused); refresh();
    }
    private void showResult(View v) {
        resultDialog = new JDialog(this, "本局结算", false); resultDialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(0, 18)); content.setBackground(PAPER);
        content.setBorder(new EmptyBorder(24, 28, 24, 28));
        Result r = v.result();
        JLabel title = new JLabel(r.winner() < 0 ? "流局" : v.players().get(r.winner()).name() + " · " + r.method());
        title.setFont(font(26, true)); title.setForeground(INK); content.add(title, BorderLayout.NORTH);
        JPanel middle = new JPanel(new BorderLayout(0, 16)); middle.setOpaque(false);
        if (r.winner() >= 0) {
            JPanel winning = new JPanel() {
                @Override protected void paintComponent(Graphics graphics) {
                    super.paintComponent(graphics); Graphics2D g = (Graphics2D) graphics.create(); quality(g);
                    int x = 4;
                    for (int t : r.winningHand()) { tile(g, x, 4, 35, 54, t, false); x += 37; }
                    for (Meld m : r.melds()) { x += 8; for (int t : m.tiles()) { tile(g, x, 4, 35, 54, t, false); x += 37; } }
                    g.dispose();
                }
            };
            winning.setOpaque(false); winning.setPreferredSize(new Dimension(720, 65)); middle.add(winning, BorderLayout.NORTH);
        }
        JPanel points = new JPanel(new GridLayout(4, 1, 0, 10)); points.setOpaque(false);
        for (int i = 0; i < 4; i++) {
            long delta = r.deltas().get(i);
            JLabel line = new JLabel(v.players().get(i).name() + "       " + (delta > 0 ? "+" : "") + delta + " 分       合计 " + v.players().get(i).score() + " 分");
            line.setFont(font(17, i == r.winner())); line.setForeground(i == r.winner() ? new Color(0x187454) : INK); points.add(line);
        }
        middle.add(points, BorderLayout.CENTER);
        JTextArea detail = new JTextArea(); detail.setEditable(false); detail.setFont(font(13, false));
        detail.setText("牌型：" + (r.patterns().isEmpty() ? "平胡 / 无" : String.join("、", r.patterns().stream().sorted().map(p -> p.label).toList()))
            + "\n" + String.join("\n", r.ledger().stream().map(p -> v.players().get(p.from()).name() + " → "
                + v.players().get(p.to()).name() + "：" + p.amount() + "分 · " + p.reason()).toList()));
        JScrollPane ledger = new JScrollPane(detail); ledger.setPreferredSize(new Dimension(720, 150));
        middle.add(ledger, BorderLayout.SOUTH); content.add(middle, BorderLayout.CENTER);
        JPanel commands = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); commands.setOpaque(false);
        JButton close = button("查看牌桌", false), again = button("下一局", true);
        close.addActionListener(e -> resultDialog.dispose()); again.addActionListener(e -> newRound(false));
        JButton review=button("查看全场牌",false); review.addActionListener(e->showReview(v));
        commands.add(review); commands.add(close); commands.add(again); content.add(commands, BorderLayout.SOUTH);
        resultDialog.setContentPane(content); resultDialog.pack(); resultDialog.setLocationRelativeTo(this); resultDialog.setVisible(true);
    }
    private void showReview(View view) {
        if(view.phase()!=Phase.OVER) return;
        if(reviewDialog!=null) reviewDialog.dispose();
        reviewDialog=RoundReview.show(this,view);
    }
}
