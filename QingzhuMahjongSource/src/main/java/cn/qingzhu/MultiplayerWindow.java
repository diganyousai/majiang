package cn.qingzhu;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.util.List;
import java.util.concurrent.ExecutionException;
import static cn.qingzhu.Game.*;
import static cn.qingzhu.RoomProtocol.*;
import static cn.qingzhu.Artwork.*;

/** One human seat. Opponents' private cards arrive only after the round ends. */
public final class MultiplayerWindow extends JFrame {
    final RoomClient client;
    final Board board=new Board();
    final JButton ready=GameWindow.button("准备",true),discard=GameWindow.button("出牌",true);
    final JButton review=GameWindow.button("查看全场牌",false);
    final JCheckBox auto=GameWindow.checkbox("本人托管");
    final JPanel actions=new JPanel(new FlowLayout(FlowLayout.RIGHT,6,0));
    private final JLabel connection=new JLabel("连接中…"),table=new JLabel("联机房间"),status=new JLabel("等待加入房间");
    private final JTextArea people=new JTextArea(),events=new JTextArea();
    private final CardLayout cards=new CardLayout();
    private final JPanel content=new JPanel(cards);
    private Snapshot latest;
    private long selectedRevision=-1;
    private int shownRound=-1;
    private String networkStatus="连接中…";
    private boolean busy;
    private SwingWorker<Game.Action,Void> worker;
    private final Timer autoTimer;
    JDialog resultDialog;
    JDialog reviewDialog;

    public MultiplayerWindow(RoomClient.Address address) {
        super("青竹扣点点 " + APP_VERSION + " · 四人联机 · "+address.nickname());
        client=new RoomClient(address); setIconImage(ICON); setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        Rectangle screen=getGraphicsConfiguration().getBounds(); Insets insets=Toolkit.getDefaultToolkit().getScreenInsets(getGraphicsConfiguration());
        int width=Math.min(1280,screen.width-insets.left-insets.right),height=Math.min(900,screen.height-insets.top-insets.bottom);
        setMinimumSize(new Dimension(Math.min(1000,width),Math.min(720,height))); setSize(width,height); setLocationRelativeTo(null);
        JPanel root=new JPanel(new BorderLayout()); setContentPane(root);
        JPanel top=new JPanel(new BorderLayout(12,0)); top.setBorder(new EmptyBorder(12,18,12,18)); top.setBackground(NAVY);
        table.setFont(font(18,true)); table.setForeground(LIGHT); connection.setForeground(GOLD); connection.setFont(font(14,false));
        JPanel tools=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0)); tools.setOpaque(false);
        JButton rules=GameWindow.button("规则",false),recovery=GameWindow.button("恢复码",false),retry=GameWindow.button("重新连接",false);
        rules.addActionListener(e->{ if(latest!=null) showText("当前房间规则",latest.rules()); });
        recovery.addActionListener(e->showText("本人恢复码（只给自己保存）",client.token().isEmpty()?"加入成功后显示":client.token()+"\n\n关闭本窗口后，可在加入房间的恢复码栏填写此码，回到原座位。"));
        retry.addActionListener(e->client.reconnect()); tools.add(rules); tools.add(recovery); tools.add(retry);
        top.add(table,BorderLayout.WEST); top.add(tools,BorderLayout.EAST); root.add(top,BorderLayout.NORTH);
        JPanel lobby=new JPanel(new BorderLayout(10,16)); lobby.setBorder(new EmptyBorder(28,35,28,35));
        people.setEditable(false); people.setFont(font(20,false)); people.setBackground(lobby.getBackground());
        lobby.add(new JLabel("四位玩家入座并分别点击准备后开局。无需启用机器人。"),BorderLayout.NORTH);
        lobby.add(people,BorderLayout.CENTER); content.add(lobby,"lobby");
        JPanel play=new JPanel(new BorderLayout()); play.add(board,BorderLayout.CENTER);
        events.setEditable(false); events.setFont(font(13,false)); events.setLineWrap(true); events.setWrapStyleWord(true);
        events.setBackground(NAVY); events.setForeground(LIGHT); events.setBorder(new EmptyBorder(16,12,16,12));
        JScrollPane history=new JScrollPane(events); history.setBorder(null); history.setPreferredSize(new Dimension(190,300)); play.add(history,BorderLayout.EAST);
        content.add(play,"play"); root.add(content,BorderLayout.CENTER);
        JPanel footer=new JPanel(new BorderLayout(6,8)); footer.setBorder(new EmptyBorder(10,16,10,16)); footer.setBackground(PAPER);
        JPanel labels=new JPanel(new GridLayout(2,1,0,5)); labels.setOpaque(false); labels.add(connection); labels.add(status); footer.add(labels,BorderLayout.NORTH);
        JPanel left=new JPanel(new FlowLayout(FlowLayout.LEFT,8,0)); left.setOpaque(false); actions.setOpaque(false);
        JButton hint=GameWindow.button("提示",false); left.add(auto); left.add(hint); left.add(ready);
        JPanel buttons=new JPanel(new BorderLayout()); buttons.setOpaque(false); buttons.setPreferredSize(new Dimension(100,52));
        buttons.add(left,BorderLayout.WEST); buttons.add(actions,BorderLayout.CENTER); footer.add(buttons,BorderLayout.SOUTH);
        root.add(footer,BorderLayout.SOUTH); board.onSelect=i->refresh(); board.onDiscard=this::discardSelected;
        discard.addActionListener(e->discardSelected());
        review.addActionListener(e->showReview());
        ready.addActionListener(e->{ if(latest!=null&&!busy&&client.connected()) { busy=true; client.ready(!latest.seats().get(latest.seat()).ready()); refresh(); } });
        hint.addActionListener(e->{ if(latest!=null&&latest.game()!=null&&!latest.game().actions().isEmpty()&&latest.game().current()==latest.seat()) {
            int tile=Bot.hint(latest.game()); board.selected=latest.game().self().hand().indexOf(tile); refresh();
        } });
        autoTimer=new Timer(350,e->launchAuto()); autoTimer.setRepeats(false); auto.addActionListener(e->refresh());
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ENTER"),"discard");
        getRootPane().getActionMap().put("discard",new AbstractAction(){ @Override public void actionPerformed(ActionEvent e){ discardSelected(); } });
        addWindowListener(new WindowAdapter(){ @Override public void windowClosed(WindowEvent e){
            autoTimer.stop(); client.close(); if(worker!=null) worker.cancel(true); if(resultDialog!=null) resultDialog.dispose(); if(reviewDialog!=null) reviewDialog.dispose();
        }});
        client.listen(m->SwingUtilities.invokeLater(()->receive(m)),s->SwingUtilities.invokeLater(()->{
            if(!isDisplayable()) return; networkStatus=s; if(!client.connected()) busy=false; refresh();
        }));
        refresh();
    }
    public void connect() { client.start(); }
    private void receive(Message message) {
        if(!isDisplayable()) return;
        if(message.snapshot()!=null) latest=message.snapshot();
        if(message.type()==Reply.ACK) {
            busy=false; if(!message.code().equals("OK")) networkStatus=message.text();
        }
        if(message.type()==Reply.ERROR) { busy=false; networkStatus=message.text(); }
        refresh();
    }
    private void refresh() {
        connection.setText(networkStatus+(client.connected()?"":" · 暂不可操作"));
        connection.setForeground(client.connected()?new Color(0x235A44):new Color(0x996014));
        if(latest==null) { ready.setEnabled(false); actions.removeAll(); actions.revalidate(); return; }
        table.setText("房间 "+latest.room()+" · "+latest.seats().get(latest.seat()).name()+" · 座位"+(latest.seat()+1));
        StringBuilder names=new StringBuilder();
        for(int seat=0;seat<4;seat++) {
            Seat p=latest.seats().get(seat);
            names.append(seat==latest.seat()?"你 → ":"       ").append(seat+1).append("  ").append(p.name()).append("    ")
                .append(!p.occupied()?"等待入座":!p.online()?"离线":p.ready()?"已准备":"在线，未准备").append("\n\n");
        }
        people.setText(names.toString());
        boolean canReady=latest.stage()==Stage.LOBBY||latest.stage()==Stage.SETTLED;
        ready.setVisible(canReady); ready.setText(latest.seats().get(latest.seat()).ready()?"取消准备":latest.stage()==Stage.SETTLED?"准备下一局":"准备");
        ready.setEnabled(canReady&&client.connected()&&!busy);
        if(latest.game()==null) { cards.show(content,"lobby"); status.setText("等待四人入座并全部准备"); return; }
        cards.show(content,"play"); Game.View view=latest.game();
        if(view.phase()!=Phase.OVER&&resultDialog!=null) { resultDialog.dispose(); resultDialog=null; }
        if(view.phase()!=Phase.OVER&&reviewDialog!=null) { reviewDialog.dispose(); reviewDialog=null; }
        if(selectedRevision!=view.revision()) { selectedRevision=view.revision(); board.selected=-1; }
        boolean usable=client.connected()&&latest.stage()==Stage.PLAYING&&!busy;
        board.view=view; board.paused=latest.stage()==Stage.PAUSED; board.auto=auto.isSelected()||!usable;
        board.repaint();
        String state=String.join("\n\n",view.events());
        if(!events.getText().equals(state)) { events.setText(state); events.setCaretPosition(events.getDocument().getLength()); }
        actions.removeAll();
        for(Kind kind:List.of(Kind.TING,Kind.PONG,Kind.KONG,Kind.CONCEALED_KONG,Kind.ADDED_KONG,Kind.WIN,Kind.PASS)) {
            List<Game.Action> choices=view.actions().stream().filter(a->a.kind()==kind).toList();
            if(choices.isEmpty()) continue; JButton b=GameWindow.button(choices.getFirst().label(),kind==Kind.WIN); b.setName(kind.name());
            b.setEnabled(usable&&!auto.isSelected()); b.addActionListener(e->choose(b,choices)); actions.add(b);
        }
        discard.setEnabled(usable&&!auto.isSelected()&&board.selected>=0&&board.selected<view.self().hand().size()
            &&view.actions().contains(new Game.Action(Kind.DISCARD,view.self().hand().get(board.selected))));
        if(view.phase()==Phase.OVER) actions.add(review);
        actions.add(discard); actions.revalidate(); actions.doLayout(); actions.repaint();
        getContentPane().revalidate();
        status.setText(latest.stage()==Stage.PAUSED?"有人离线，整桌暂停；对方重连后继续":latest.stage()==Stage.SETTLED?"本局已结算，四人准备后开始下一局":
            busy?"操作提交中…":view.actions().isEmpty()?"等待其他玩家行动":auto.isSelected()?"本人托管中":"轮到你操作，请选择出牌或响应按钮");
        if(view.phase()==Phase.OVER&&shownRound!=view.round()) { shownRound=view.round(); showResult(view); }
        if(auto.isSelected()&&usable&&!view.actions().isEmpty()&&worker==null&&!autoTimer.isRunning()) autoTimer.restart();
    }
    private void choose(JButton source,List<Game.Action> choices) {
        if(choices.size()==1) { submit(choices.getFirst()); return; }
        JPopupMenu menu=new JPopupMenu();
        for(Game.Action a:choices) {
            JMenuItem option=new JMenuItem((a.kind()==Kind.TING?"打出并报听：":"")+Tiles.name(a.tile()));
            option.addActionListener(e->submit(a)); menu.add(option);
        }
        menu.show(source,0,-menu.getPreferredSize().height);
    }
    void discardSelected() {
        if(latest==null||latest.game()==null||board.selected<0||auto.isSelected()) return;
        if(board.selected>=latest.game().self().hand().size()) return;
        submit(new Game.Action(Kind.DISCARD,latest.game().self().hand().get(board.selected)));
    }
    void submit(Game.Action action) {
        if(latest==null||latest.game()==null||latest.stage()!=Stage.PLAYING||!client.connected()||busy||!latest.game().actions().contains(action)) return;
        busy=true; client.act(action); refresh();
    }
    private void launchAuto() {
        if(!auto.isSelected()||latest==null||latest.game()==null||latest.stage()!=Stage.PLAYING||busy||!client.connected()||worker!=null||latest.game().actions().isEmpty()) return;
        Snapshot source=latest;
        worker=new SwingWorker<>() {
            @Override protected Game.Action doInBackground(){ return Bot.choose(source.game()); }
            @Override protected void done(){
                worker=null; if(!isDisplayable()) return;
                try { Game.Action a=get(); if(auto.isSelected()&&latest!=null&&latest.context()==source.context()&&latest.game().round()==source.game().round()) submit(a); }
                catch(java.util.concurrent.CancellationException ignored) { }
                catch(InterruptedException e){ Thread.currentThread().interrupt(); }
                catch(ExecutionException e){ auto.setSelected(false); networkStatus="托管失败，请手动操作"; }
                refresh();
            }
        }; worker.execute();
    }
    private void showText(String title,String text) {
        JTextArea area=new JTextArea(text,18,55); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true); area.setFont(font(14,false));
        JOptionPane.showMessageDialog(this,new JScrollPane(area),title,JOptionPane.PLAIN_MESSAGE);
    }
    private void showReview() {
        if(latest==null||latest.game()==null||latest.game().phase()!=Phase.OVER) return;
        if(reviewDialog!=null) reviewDialog.dispose();
        reviewDialog=RoundReview.show(this,latest.game());
    }
    private void showResult(Game.View view) {
        if(resultDialog!=null) resultDialog.dispose();
        resultDialog=new JDialog(this,"联机结算 · 第"+view.round()+"局",false);
        JTextArea details=new JTextArea(18,55); details.setEditable(false); details.setFont(font(15,false));
        Result r=view.result(); StringBuilder text=new StringBuilder(r.winner()<0?"荒庄\n\n":view.players().get(r.winner()).name()+" · "+r.method()+"\n\n");
        for(int i=0;i<4;i++) text.append(view.players().get(i).name()).append("：本局 ").append(r.deltas().get(i)).append("，总分 ").append(view.players().get(i).score()).append('\n');
        text.append("\n付款明细\n"); for(var p:r.ledger()) text.append(view.players().get(p.from()).name()).append(" → ").append(view.players().get(p.to()).name()).append("：").append(p.amount()).append(" · ").append(p.reason()).append('\n');
        details.setText(text.toString()); resultDialog.add(new JScrollPane(details),BorderLayout.CENTER);
        JButton next=GameWindow.button("准备下一局",true); next.addActionListener(e->{ if(latest!=null&&!latest.seats().get(latest.seat()).ready()&&client.connected()) client.ready(true); resultDialog.dispose(); });
        JPanel commands=new JPanel(new FlowLayout(FlowLayout.RIGHT,8,0));
        JButton all=GameWindow.button("查看全场牌",false); all.addActionListener(e->showReview()); commands.add(all); commands.add(next);
        resultDialog.add(commands,BorderLayout.SOUTH); resultDialog.pack(); resultDialog.setLocationRelativeTo(this); resultDialog.setVisible(true);
    }
}
