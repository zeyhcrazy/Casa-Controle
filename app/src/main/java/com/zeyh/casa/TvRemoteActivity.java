package com.zeyh.casa;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.concurrent.*;

public class TvRemoteActivity extends Activity {
    final ExecutorService network=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(12),new ThreadPoolExecutor.AbortPolicy());
    final Handler main=new Handler(Looper.getMainLooper());
    SharedPreferences prefs;TextView state;volatile DeviceControl.Tv tv;volatile DeviceControl.Pointer pointer;volatile boolean active;volatile int generation;
    @Override public void onCreate(Bundle saved){
        super.onCreate(saved);prefs=getSharedPreferences("casa",MODE_PRIVATE);
        LinearLayout page=RemoteUi.column(this);page.setPadding(20,20,20,20);page.setBackgroundColor(RemoteUi.BG);
        page.setOnApplyWindowInsetsListener((v,in)->{android.graphics.Insets bars=in.getInsets(WindowInsets.Type.systemBars());int p=RemoteUi.dp(this,20);page.setPadding(p+bars.left,p+bars.top,p+bars.right,p+bars.bottom);return in;});
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.addView(page);setContentView(scroll);
        RemoteUi.row(this,page,new String[]{"Fechar","TV da sala"},new Runnable[]{()->finish(),()->connect()});
        state=RemoteUi.text(this,"Conectando ao controle…",15,RemoteUi.MUTED);page.addView(state);RemoteUi.gap(this,page,12);
        RemoteUi.row(this,page,new String[]{"Home","Voltar","Ajustes"},new Runnable[]{()->key("HOME"),()->key("BACK"),()->key("MENU")});
        RemoteUi.gap(this,page,12);
        RemoteUi.row(this,page,new String[]{"","↑",""},new Runnable[]{null,()->key("UP"),null});
        RemoteUi.row(this,page,new String[]{"←","OK","→"},new Runnable[]{()->key("LEFT"),()->key("ENTER"),()->key("RIGHT")});
        RemoteUi.row(this,page,new String[]{"","↓",""},new Runnable[]{null,()->key("DOWN"),null});
        RemoteUi.gap(this,page,12);
        RemoteUi.row(this,page,new String[]{"Vol −","Mudo","Vol +"},new Runnable[]{()->key("VOLUMEDOWN"),()->key("MUTE"),()->key("VOLUMEUP")});
        RemoteUi.row(this,page,new String[]{"⏪","Play","Pausar","⏩"},new Runnable[]{()->key("REWIND"),()->key("PLAY"),()->key("PAUSE"),()->key("FASTFORWARD")});
        RemoteUi.row(this,page,new String[]{"Digitar na TV","Reconectar"},new Runnable[]{()->keyboard(),()->connect()});
        RemoteUi.gap(this,page,12);page.addView(RemoteUi.text(this,"Use as setas para navegar. Para digitar, selecione primeiro um campo de texto na TV.",14,RemoteUi.MUTED));
    }
    @Override protected void onResume(){super.onResume();active=true;connect();}
    @Override protected void onPause(){active=false;generation++;close();super.onPause();}
    @Override protected void onDestroy(){close();network.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy();}
    void close(){DeviceControl.Pointer p=pointer;pointer=null;DeviceControl.Tv t=tv;tv=null;try{if(p!=null)p.close();}catch(Exception ignored){}try{if(t!=null)t.close();}catch(Exception ignored){}}
    void submit(Runnable job){try{network.execute(job);}catch(RejectedExecutionException ignored){state.setText("Aguarde o comando anterior.");}}
    void connect(){
        if(!active)return;int request=++generation;close();state.setText("Conectando ao controle…");
        submit(()->{DeviceControl.Tv conn=null;DeviceControl.Pointer input=null;
            if(!active||request!=generation)return;
            try{
                conn=new DeviceControl.Tv(prefs);String path=conn.request("ssap://com.webos.service.networkinput/getPointerInputSocket",new JSONObject()).getString("socketPath");input=new DeviceControl.Pointer(prefs,path);
                if(!active||request!=generation){input.close();conn.close();return;}tv=conn;pointer=input;
                main.post(()->{if(active&&request==generation)state.setText("Conectado · pronto para navegar");});
            }catch(Exception e){try{if(input!=null)input.close();if(conn!=null)conn.close();}catch(Exception ignored){}error(e,request);}
        });
    }
    void error(Exception e,int request){main.post(()->{
        if(!active||request!=generation)return;
        state.setText("Não conectou: "+(e.getMessage()==null?"confira a TV e o Wi-Fi":e.getMessage()));
        if(e instanceof DeviceControl.PairingNeeded){String fp=((DeviceControl.PairingNeeded)e).fingerprint;
            new AlertDialog.Builder(this).setTitle("Confirmar TV").setMessage("IP: "+prefs.getString("tv_host","192.168.1.10")+"\nCertificado: "+fp).setNegativeButton("Voltar",null).setPositiveButton("Confiar",(d,w)->{prefs.edit().putString("tv_pin",fp).apply();connect();}).show();
        }else if(String.valueOf(e.getMessage()).matches("(?is).*(401|403|permission|unauthor|denied).*")){
            new AlertDialog.Builder(this).setTitle("Autorizar o controle da TV").setMessage("Esta função precisa de acesso às teclas. Ao continuar, escolha Permitir no pedido exibido pela TV.")
                .setNegativeButton("Voltar",null).setPositiveButton("Autorizar",(d,w)->{prefs.edit().remove("tv_key").apply();connect();}).show();
        }
    });}
    void key(String name){int request=generation;submit(()->{if(!active||request!=generation)return;try{DeviceControl.Pointer p=pointer;if(p==null)throw new Exception("toque em Reconectar");p.button(name);}catch(Exception e){error(e,request);}});}
    void keyboard(){EditText edit=new EditText(this);edit.setHint("Texto para o campo selecionado");edit.setMaxLines(3);
        new AlertDialog.Builder(this).setTitle("Digitar na TV").setView(edit).setNegativeButton("Voltar",null).setPositiveButton("Enviar",(d,w)->{
            String text=edit.getText().toString();if(text.isEmpty())return;int request=generation;
            submit(()->{try{DeviceControl.Tv t=tv;if(t==null)throw new Exception("toque em Reconectar");t.request("ssap://com.webos.service.ime/insertText",new JSONObject().put("text",text).put("replace",0));}catch(Exception e){error(e,request);}});
        }).show();
    }
}
