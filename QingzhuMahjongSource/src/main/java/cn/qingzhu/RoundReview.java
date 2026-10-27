package cn.qingzhu;

import javax.swing.*;
import java.awt.*;
import static cn.qingzhu.Artwork.*;
import static cn.qingzhu.Game.*;

/** An immutable, public end-of-round snapshot. Never accepts an active-round view. */
final class RoundReview {
    private RoundReview() {}
    static JDialog show(Window owner,View view) {
        if(view.phase()!=Phase.OVER) throw new IllegalArgumentException("本局结束后才能查看全场牌");
        JDialog dialog=new JDialog(owner,"第 "+view.round()+" 局 · 全场牌",Dialog.ModalityType.MODELESS);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel content=new JPanel(); content.setLayout(new BoxLayout(content,BoxLayout.Y_AXIS)); content.setBackground(TABLE_EDGE);
        Result result=view.result();
        JLabel title=new JLabel(result.winner()<0?"荒庄 · 全场牌":view.players().get(result.winner()).name()+" · "+result.method()+" · 胡牌："+Tiles.name(result.tile()));
        title.setForeground(LIGHT); title.setFont(font(20,true)); title.setBorder(BorderFactory.createEmptyBorder(16,20,16,20));
        content.add(title);
        for(int seat=0;seat<4;seat++) content.add(new PlayerCards(view,seat));
        JScrollPane scroll=new JScrollPane(content); scroll.getVerticalScrollBar().setUnitIncrement(24); dialog.add(scroll,BorderLayout.CENTER);
        JButton close=GameWindow.button("关闭",false); close.addActionListener(e->dialog.dispose()); dialog.add(close,BorderLayout.SOUTH);
        Rectangle screen=GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        dialog.setSize(Math.min(1080,screen.width),Math.min(800,screen.height)); dialog.setLocationRelativeTo(owner); dialog.setVisible(true);
        return dialog;
    }
    private static final class PlayerCards extends JPanel {
        private final View view; private final int seat;
        PlayerCards(View view,int seat) {
            this.view=view; this.seat=seat;
            int rows=Math.max(1,(view.players().get(seat).river().size()+27)/28);
            Dimension size=new Dimension(1020,108+rows*41);
            setPreferredSize(size); setMinimumSize(size); setMaximumSize(new Dimension(Integer.MAX_VALUE,size.height)); setBackground(TABLE_EDGE);
        }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics); Graphics2D g=(Graphics2D)graphics.create(); quality(g);
            PlayerView player=view.players().get(seat);
            g.setColor(new Color(0x548178)); g.drawLine(20,0,getWidth()-20,0);
            text(g,player.name()+(seat==view.dealer()?" · 庄":"")+(seat==view.result().winner()?" · 胡":""),20,24,17,GOLD,true);
            text(g,"手牌",20,67,14,MUTED,false); int x=80;
            for(int tile:player.hand()) { tile(g,x,36,38,56,tile,false); x+=41; }
            for(Meld meld:player.melds()) {
                x+=16; text(g,new Game.Action(meld.kind(),-1).label(),x,27,13,MUTED,false);
                for(int tile:meld.tiles()) { tile(g,x,42,33,49,tile,false); x+=35; }
            }
            text(g,"弃牌",20,132,14,MUTED,false);
            for(int i=0;i<player.river().size();i++) tile(g,80+i%28*32,108+i/28*41,29,39,player.river().get(i),false);
            if(player.river().isEmpty()) text(g,"无",80,132,14,MUTED,false);
            g.dispose();
        }
    }
}
