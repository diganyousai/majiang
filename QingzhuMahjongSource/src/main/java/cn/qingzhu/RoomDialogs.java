package cn.qingzhu;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.*;
import java.net.*;
import java.util.*;
import static cn.qingzhu.Artwork.*;

/** Hosting is independent of the host's player window. */
final class RoomDialogs {
    private RoomDialogs() {}
    static void launch() {
        String[] options={"单机练习","创建联机房间","加入联机房间"};
        int choice=JOptionPane.showOptionDialog(null,"青竹扣点点 " + APP_VERSION + "\n选择单机练习，或与三位朋友联机对局。","开始游戏",
            JOptionPane.DEFAULT_OPTION,JOptionPane.PLAIN_MESSAGE,null,options,options[1]);
        if(choice==0) { Rules rules=RulesDialog.choose(null); if(rules!=null) new GameWindow(System.nanoTime(),rules).setVisible(true); }
        if(choice==1) host(); if(choice==2) join();
    }
    private static void host() {
        Rules rules=RulesDialog.choose(null,true); if(rules==null) return;
        JTextField name=new JTextField("房主"),bind=new JTextField("0.0.0.0"),port=new JTextField("17890"),code=new JTextField(RoomServer.newCode());
        JPasswordField password=new JPasswordField(RoomServer.newCode()); JPanel form=new JPanel(new GridLayout(0,2,12,10));
        form.add(new JLabel("你的昵称")); form.add(name); form.add(new JLabel("监听地址（所有网卡为0.0.0.0）")); form.add(bind);
        form.add(new JLabel("端口（TCP）")); form.add(port); form.add(new JLabel("房间号")); form.add(code); form.add(new JLabel("房间口令（至少6位）")); form.add(password);
        while(JOptionPane.showConfirmDialog(null,form,"主机开房",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)==JOptionPane.OK_OPTION) try {
            int number=Integer.parseInt(port.getText().trim()); if(number<1||number>65535) throw new IllegalArgumentException("端口需1～65535");
            String secret=new String(password.getPassword());
            RoomServer server=new RoomServer(bind.getText().trim(),number,code.getText().trim(),secret,name.getText().trim(),rules);
            new HostWindow(server,secret,name.getText().trim(),bind.getText().trim()).setVisible(true); return;
        } catch(Exception e) { JOptionPane.showMessageDialog(null,"开房失败："+e.getMessage(),"检查主机设置",JOptionPane.ERROR_MESSAGE); }
    }
    private static void join() {
        JTextField host=new JTextField("127.0.0.1"),port=new JTextField("17890"),room=new JTextField(),name=new JTextField("玩家");
        JPasswordField password=new JPasswordField(); JPanel form=new JPanel(new GridLayout(0,2,12,10));
        form.add(new JLabel("主机IP（不要填0.0.0.0）")); form.add(host); form.add(new JLabel("端口")); form.add(port);
        form.add(new JLabel("房间号")); form.add(room); form.add(new JLabel("房间口令")); form.add(password);
        form.add(new JLabel("你的昵称（回来时保持相同）")); form.add(name);
        form.add(new JLabel("自动恢复原座位")); form.add(new JLabel("原电脑、相同昵称，无需恢复码"));
        while(JOptionPane.showConfirmDialog(null,form,"加入联机房间",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)==JOptionPane.OK_OPTION) try {
            int number=Integer.parseInt(port.getText().trim()); if(number<1||number>65535) throw new IllegalArgumentException("端口需1～65535");
            if(host.getText().isBlank()||room.getText().isBlank()||name.getText().isBlank()) throw new IllegalArgumentException("请填主机地址、房间号与昵称");
            MultiplayerWindow window=new MultiplayerWindow(new RoomClient.Address(host.getText().trim(),number,room.getText().trim(),new String(password.getPassword()),name.getText().trim(),""));
            window.setVisible(true); window.connect(); return;
        } catch(IllegalArgumentException|IllegalStateException e) { JOptionPane.showMessageDialog(null,e.getMessage(),"检查加入信息",JOptionPane.WARNING_MESSAGE); }
    }
    private static final class HostWindow extends JFrame {
        final RoomServer server; final javax.swing.Timer timer; MultiplayerWindow player;
        HostWindow(RoomServer server,String password,String name,String bind) {
            super("青竹扣点点 · 主机房间 "+server.room()); this.server=server;
            setIconImage(ICON); setDefaultCloseOperation(DO_NOTHING_ON_CLOSE); setSize(620,420); setLocationRelativeTo(null);
            JPanel panel=new JPanel(new BorderLayout(12,18)); panel.setBorder(new EmptyBorder(22,24,22,24)); setContentPane(panel);
            JTextArea instructions=new JTextArea(); instructions.setEditable(false); instructions.setFont(font(15,false)); instructions.setLineWrap(true); instructions.setWrapStyleWord(true);
            instructions.setText("房间号："+server.room()+"\n房间口令："+password+"\n端口："+server.port()+"（TCP）\n监听："+bind
                +"\n\n本机测试填 127.0.0.1；朋友填主机的局域网或Tailscale IP。\n本机网卡地址："+addresses()
                +"\n\n保持此主机窗口打开。关闭自己的牌桌不关闭服务，其他玩家会等待你重连。四人全部准备后开局。");
            panel.add(new JScrollPane(instructions),BorderLayout.CENTER); JLabel status=new JLabel(); panel.add(status,BorderLayout.NORTH);
            JButton enter=GameWindow.button("打开我的牌桌",true); enter.addActionListener(e->{
                if(player!=null&&player.isDisplayable()) { player.toFront(); return; }
                String target=bind.equals("0.0.0.0")||bind.equals("::")?"127.0.0.1":bind;
                player=new MultiplayerWindow(new RoomClient.Address(target,server.port(),server.room(),password,name,server.hostToken()));
                player.setVisible(true); player.connect();
            }); panel.add(enter,BorderLayout.SOUTH); timer=new javax.swing.Timer(1000,e->status.setText(server.summary())); timer.start();
            addWindowListener(new WindowAdapter(){
                @Override public void windowClosing(WindowEvent e){ if(JOptionPane.showConfirmDialog(HostWindow.this,"关闭主机将中断所有玩家的对局，确定关闭？","关闭房间",JOptionPane.YES_NO_OPTION)==JOptionPane.YES_OPTION) dispose(); }
                @Override public void windowClosed(WindowEvent e){ timer.stop(); server.close(); if(player!=null) player.dispose(); }
            }); SwingUtilities.invokeLater(enter::doClick);
        }
    }
    private static String addresses() {
        try {
            Set<String> result=new TreeSet<>();
            for(NetworkInterface adapter:Collections.list(NetworkInterface.getNetworkInterfaces())) if(adapter.isUp()&&!adapter.isLoopback())
                for(InetAddress address:Collections.list(adapter.getInetAddresses())) if(address instanceof Inet4Address) result.add(address.getHostAddress());
            return String.join("，",result);
        } catch(SocketException e) { return "请使用 ipconfig 查看"; }
    }
}
