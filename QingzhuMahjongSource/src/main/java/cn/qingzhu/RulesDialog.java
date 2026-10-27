package cn.qingzhu;

import javax.swing.*;
import java.awt.*;
import java.util.*;

/** Room creation controls. Unconfirmed scoring amounts are intentionally blank. */
final class RulesDialog {
    private RulesDialog() {}
    static Rules choose(Component owner) {
        return choose(owner,false);
    }
    static Rules choose(Component owner,boolean online) {
        JComboBox<String> mode=new JComboBox<>(new String[]{"亲友房","金币场（模拟积分）"});
        JCheckBox ting=new JCheckBox("必须报听才能胡",true), follows=new JCheckBox("杠随胡走"), mouths=new JCheckBox("风嘴子");
        JSpinner minimum=new JSpinner(new SpinnerNumberModel(6,3,10,1));
        JComboBox<String> gang=new JComboBox<>(new String[]{"听前杠包杠","点杠者包杠","点杠者不包杠"});
        JComboBox<String> liability=new JComboBox<>(new String[]{"无额外包杠","点炮包胡包杠","未报听点炮包杠"});
        JComboBox<String> wild=new JComboBox<>(new String[]{"关闭","捉耗子","风耗子","捉双耗子"});
        JComboBox<String> scoring=new JComboBox<>(new String[]{"关闭","翻倍","加点"});
        JComboBox<String> dealer=new JComboBox<>(new String[]{"关闭","5分","10分"});
        JPanel basic=new JPanel(new GridLayout(0,2,12,10));
        basic.add(new JLabel("玩法")); basic.add(mode);
        basic.add(ting); basic.add(new JLabel("报听弃牌隐藏，禁止碰杠胡"));
        basic.add(new JLabel("报听至少有一个听口达到点数")); basic.add(minimum);
        basic.add(new JLabel("点杠付分")); basic.add(gang);
        basic.add(new JLabel("额外承包规则（互斥）")); basic.add(liability);
        basic.add(follows); basic.add(mouths);
        basic.add(new JLabel("耗子（三选一，关闭则无万能牌）")); basic.add(wild);
        basic.add(new JLabel("带庄")); basic.add(dealer);
        basic.add(new JLabel("跟庄：判定待确认，未启用")); basic.add(new JLabel("点炮者包胡家杠：不启用"));
        JPanel patternPanel=new JPanel(new GridLayout(0,2,12,8));
        patternPanel.add(new JLabel("牌型计分")); patternPanel.add(scoring);
        Map<Rules.Pattern,JCheckBox> boxes=new EnumMap<>(Rules.Pattern.class);
        Map<Rules.Pattern,JTextField> values=new EnumMap<>(Rules.Pattern.class);
        for(Rules.Pattern pattern:Rules.Pattern.values()) if(pattern!=Rules.Pattern.MIXED) {
            JCheckBox enabled=new JCheckBox(pattern.label); JTextField value=new JTextField(6);
            value.setToolTipText("数值待定：勾选后请自行填写整数 1～16；留空不会启用");
            boxes.put(pattern,enabled); values.put(pattern,value); patternPanel.add(enabled); patternPanel.add(value);
        }
        JLabel explanation=new JLabel("<html>默认均关闭，数值留空；选择耗子后禁用翻倍/加点。<br>七对与豪华七对按实际牌型择一计分。金币场使用给定固定倍数。</html>");
        JPanel wrapper=new JPanel(new BorderLayout(0,12)); wrapper.add(patternPanel,BorderLayout.CENTER); wrapper.add(explanation,BorderLayout.SOUTH);
        JTabbedPane tabs=new JTabbedPane(); tabs.addTab("基本设置",basic); tabs.addTab("牌型计分（数值待定）",wrapper);
        JPanel panel=new JPanel(new BorderLayout(0,12)); panel.setBorder(BorderFactory.createEmptyBorder(10,10,10,10));
        panel.add(new JLabel("<html>山西扣点点 · "+(online?"四人联机房间":"本地单机（你 + 3 个机器人）")+"<br>1/2点不胡；3/4/5点仅自摸；6～10点可自摸或点炮。</html>"),BorderLayout.NORTH);
        panel.add(tabs,BorderLayout.CENTER);
        Runnable update=()->{
            boolean friends=mode.getSelectedIndex()==0, hasWild=wild.getSelectedIndex()!=0;
            for(JComponent c:new JComponent[]{ting,minimum,gang,liability,follows,mouths,wild,dealer}) c.setEnabled(friends);
            if((hasWild || !friends) && scoring.getSelectedIndex()!=0) scoring.setSelectedIndex(0);
            scoring.setEnabled(friends&&!hasWild);
            boolean patterns=friends&&!hasWild&&scoring.getSelectedIndex()!=0;
            boxes.forEach((p,box)->{ box.setEnabled(patterns); values.get(p).setEnabled(patterns&&box.isSelected()); });
        };
        mode.addActionListener(e->{
            if(mode.getSelectedIndex()==1) {
                ting.setSelected(true); minimum.setValue(3); gang.setSelectedIndex(0); liability.setSelectedIndex(0);
                follows.setSelected(false); mouths.setSelected(false); wild.setSelectedIndex(0); dealer.setSelectedIndex(0);
            } else minimum.setValue(6);
            update.run();
        });
        wild.addActionListener(e->update.run()); scoring.addActionListener(e->update.run());
        boxes.values().forEach(box->box.addActionListener(e->update.run())); update.run();
        while(true) {
            if(JOptionPane.showConfirmDialog(owner,panel,"创建扣点点牌桌",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)!=JOptionPane.OK_OPTION) return null;
            if(mode.getSelectedIndex()==1) return Rules.coins();
            try {
                Rules.Scoring selected=Rules.Scoring.values()[scoring.getSelectedIndex()];
                Map<Rules.Pattern,Integer> amounts=new EnumMap<>(Rules.Pattern.class);
                if(selected!=Rules.Scoring.NONE) for(var entry:boxes.entrySet()) if(entry.getValue().isSelected()) {
                    String input=values.get(entry.getKey()).getText().trim();
                    if(input.isEmpty()) throw new IllegalArgumentException(entry.getKey().label+"的数值仍为空，请填写或取消勾选");
                    int amount;
                    try { amount=Integer.parseInt(input); } catch(NumberFormatException e) { throw new IllegalArgumentException("牌型数值需为整数"); }
                    amounts.put(entry.getKey(),amount);
                }
                return new Rules(Rules.Mode.FRIENDS,ting.isSelected(),(Integer)minimum.getValue(),Rules.GangPolicy.values()[gang.getSelectedIndex()],
                    follows.isSelected(),Rules.Liability.values()[liability.getSelectedIndex()],selected,amounts,Rules.Wild.values()[wild.getSelectedIndex()],
                    mouths.isSelected(),false,new int[]{0,5,10}[dealer.getSelectedIndex()],Rules.Hidden.DECLARATION);
            } catch(IllegalArgumentException ex) { JOptionPane.showMessageDialog(owner,ex.getMessage(),"请检查设置",JOptionPane.WARNING_MESSAGE); }
        }
    }
}
