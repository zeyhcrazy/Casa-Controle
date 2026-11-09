package com.zeyh.casa;

import android.app.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

final class RemoteUi {
    static final int INK=Color.rgb(22,50,79),BLUE=Color.rgb(30,98,220),BG=Color.rgb(240,244,248),MUTED=Color.rgb(89,110,129);
    static int dp(Activity a,float n){return Math.round(n*a.getResources().getDisplayMetrics().density);}
    static TextView text(Activity a,String s,int size,int color){TextView t=new TextView(a);t.setText(s);t.setTextSize(size);t.setTextColor(color);return t;}
    static GradientDrawable background(Activity a,int color,int radius){GradientDrawable b=new GradientDrawable();b.setColor(color);b.setCornerRadius(dp(a,radius));return b;}
    static Button button(Activity a,String title,boolean primary,Runnable click){Button b=new Button(a);b.setText(title);b.setAllCaps(false);b.setTextSize(16);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setTextColor(primary?Color.WHITE:INK);b.setBackground(background(a,primary?BLUE:Color.rgb(225,234,244),12));b.setPadding(dp(a,8),dp(a,8),dp(a,8),dp(a,8));b.setOnClickListener(v->click.run());return b;}
    static LinearLayout column(Activity a){LinearLayout l=new LinearLayout(a);l.setOrientation(LinearLayout.VERTICAL);return l;}
    static void gap(Activity a,LinearLayout l,int height){l.addView(new Space(a),new LinearLayout.LayoutParams(1,dp(a,height)));}
    static void row(Activity a,LinearLayout parent,String[] titles,Runnable[] clicks){LinearLayout row=new LinearLayout(a);for(int i=0;i<titles.length;i++){View b=titles[i].isEmpty()?new Space(a):button(a,titles[i],titles[i].equals("OK"),clicks[i]);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(a,62),1);p.setMargins(dp(a,4),dp(a,4),dp(a,4),dp(a,4));row.addView(b,p);}parent.addView(row);}
}
