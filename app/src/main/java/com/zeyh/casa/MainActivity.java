package com.zeyh.casa;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.*;
import android.os.*;
import android.net.*;
import android.text.InputType;
import android.util.Pair;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    static final int INK=Color.rgb(22,50,79),BLUE=Color.rgb(30,98,220),MUTED=Color.rgb(89,110,129),BG=Color.rgb(240,244,248);
    static final int GREEN=Color.rgb(22,120,77),AMBER=Color.rgb(147,96,17),RED=Color.rgb(177,48,48);
    final ExecutorService worker=Executors.newFixedThreadPool(2);
    final ExecutorService statusWorker=Executors.newFixedThreadPool(2);
    final Handler handler=new Handler(Looper.getMainLooper());
    final Set<String> busy=new HashSet<>(),checking=new HashSet<>();
    final Map<String,Long> waking=new HashMap<>();
    final Map<String,Long> searched=new ConcurrentHashMap<>();
    final Map<String,Object> searchLocks=Map.of("tv",new Object(),"pc",new Object());
    boolean foreground=false;
    int generation=0;
    final Runnable ticker=new Runnable(){public void run(){if(!foreground)return;poll("tv");poll("pc");handler.postDelayed(this,8000);}};
    final ArrayList<Button> actions=new ArrayList<>();
    SharedPreferences prefs;LinearLayout page;TextView tvStatus,pcStatus,message;Button cancel,pcConnect;
    interface Work {String run() throws Exception;}
    int dp(float n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle state){super.onCreate(state);prefs=getSharedPreferences("casa",MODE_PRIVATE);prefs.edit().remove("pc_anydesk_id").apply();build();}
    TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);t.setFontFeatureSettings("kern");return t;}
    GradientDrawable background(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    LinearLayout vertical(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    void gap(LinearLayout l,int height){Space s=new Space(this);l.addView(s,new LinearLayout.LayoutParams(1,dp(height)));}
    Button button(String title,boolean primary,String device,Runnable run){Button b=new Button(this);b.setText(title);b.setTextSize(16);b.setAllCaps(false);b.setTypeface(android.graphics.Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setMinHeight(dp(54));b.setMinimumHeight(dp(54));b.setTextColor(primary?Color.WHITE:INK);b.setBackground(background(primary?BLUE:Color.rgb(229,237,245),12));b.setPadding(dp(12),dp(10),dp(12),dp(10));b.setTag(device);b.setOnClickListener(v->run.run());actions.add(b);return b;}
    void buttons(LinearLayout card,Button left,Button right){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(54),1);lp.setMarginEnd(dp(6));row.addView(left,lp);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,dp(54),1);rp.setMarginStart(dp(6));row.addView(right,rp);card.addView(row);}
    LinearLayout card(String name,String detail,int kind){LinearLayout c=vertical();c.setPadding(dp(20),dp(20),dp(20),dp(20));c.setBackground(background(Color.WHITE,20));LinearLayout head=new LinearLayout(this);head.setGravity(Gravity.CENTER_VERTICAL);head.addView(new DeviceIcon(this,kind),new LinearLayout.LayoutParams(dp(42),dp(42)));LinearLayout titles=vertical();LinearLayout.LayoutParams tl=new LinearLayout.LayoutParams(0,-2,1);tl.setMarginStart(dp(14));TextView title=text(name,22,INK);title.setTypeface(android.graphics.Typeface.create("sans-serif-medium",Typeface.NORMAL));titles.addView(title);titles.addView(text(detail,14,MUTED));head.addView(titles,tl);c.addView(head);gap(c,18);LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2);cp.bottomMargin=dp(16);page.addView(c,cp);return c;}
    void build(){generation++;actions.clear();waking.clear();ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BG);page=vertical();page.setPadding(dp(20),dp(20),dp(20),dp(20));scroll.addView(page);scroll.setOnApplyWindowInsetsListener((v,in)->{android.graphics.Insets bars=in.getInsets(WindowInsets.Type.systemBars());page.setPadding(dp(20)+bars.left,dp(20)+bars.top,dp(20)+bars.right,dp(20)+bars.bottom);return in;});setContentView(scroll);
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);TextView name=text("Casa",36,INK);name.setTypeface(android.graphics.Typeface.create("sans-serif-medium",Typeface.NORMAL));heading.addView(name,new LinearLayout.LayoutParams(0,-2,1));Button config=button("Ajustes",false,"settings",()->settings());heading.addView(config,new LinearLayout.LayoutParams(dp(100),dp(48)));page.addView(heading);gap(page,4);page.addView(text("Seu controle, no Wi-Fi de casa",16,MUTED));gap(page,24);
        LinearLayout tv=card("TV da sala","LG 55NANO80TSA",0);tvStatus=text("● Verificando…",14,MUTED);tv.addView(tvStatus);gap(tv,16);buttons(tv,button("Ligar",true,"tv",()->wake("tv")),button("Desligar",false,"tv",()->confirmTvOff()));gap(tv,12);tv.addView(button("Conectar / verificar TV",false,"tv",()->tv(false)));gap(tv,10);tv.addView(button("Controle remoto",false,"tv",()->startActivity(new Intent(this,TvRemoteActivity.class))));
        LinearLayout pc=card("Computador","Zeyh Tweaks",1);pcStatus=text("● Verificando…",14,MUTED);pc.addView(pcStatus);gap(pc,16);buttons(pc,button("Ligar",true,"pc",()->wake("pc")),button("Desligar",false,"pc",()->confirmPcOff()));gap(pc,12);pcConnect=button(prefs.contains("pc_token")?"Verificar computador":"Conectar computador",false,"pc",()->{if(prefs.contains("pc_token"))checkPc();else pairPc();});pc.addView(pcConnect);gap(pc,10);pc.addView(button("Abrir AnyDesk no PC",false,"pc",()->openAnyDeskPc()));gap(pc,8);cancel=button("Cancelar desligamento",false,"pc",()->pcAction("/api/cancel"));cancel.setVisibility(View.GONE);pc.addView(cancel);
        LinearLayout ac=card("Ar-condicionado","Philco PAC12QC",2);ac.addView(text("Aguardando conexão",16,MUTED));gap(ac,6);ac.addView(text("A conexão do ar com o app ainda precisa ser confirmada.",14,MUTED));
        message=text("O estado atualiza sozinho a cada 8 segundos enquanto o app está aberto. Sem resposta na rede, o aparelho pode estar desligado ou desconectado.",14,MUTED);message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);page.addView(message);gap(page,18);updateButtons();pollNow();
    }
    @Override public void onResume(){super.onResume();foreground=true;pollNow();}
    @Override public void onPause(){foreground=false;generation++;handler.removeCallbacks(ticker);super.onPause();}
    void updateButtons(){for(Button b:actions){String d=(String)b.getTag();b.setEnabled(d.equals("settings")?busy.isEmpty():!busy.contains(d));}}
    void pollNow(){handler.removeCallbacks(ticker);if(foreground)handler.post(ticker);}
    void poll(String device){
        if(!foreground||busy.contains(device)||checking.contains(device))return;
        checking.add(device);final int requestGeneration=generation;
        statusWorker.submit(()->{
            String before=host(device);
            DeviceControl.StateInfo state=device.equals("tv")?DeviceControl.tvState(prefs):DeviceControl.pcState(prefs);
            if((state.kind==DeviceControl.UNREACHABLE||state.kind==DeviceControl.ERROR)&&relocate(device,false,before))
                state=device.equals("tv")?DeviceControl.tvState(prefs):DeviceControl.pcState(prefs);
            final DeviceControl.StateInfo shown=state;
            handler.post(()->{checking.remove(device);if(!foreground||isDestroyed())return;
                if(requestGeneration!=generation){poll(device);return;}
                if(busy.contains(device))return;showState(device,shown);
            });
        });
    }
    void showState(String device,DeviceControl.StateInfo state){
        TextView target=device.equals("tv")?tvStatus:pcStatus;
        Long deadline=waking.get(device);
        if(deadline!=null){if(state.kind==DeviceControl.ON||SystemClock.elapsedRealtime()>deadline)waking.remove(device);else{target.setText(R.string.waking);target.setTextColor(AMBER);return;}}
        target.setText(getString(R.string.device_state,state.label));
        target.setTextColor(state.kind==DeviceControl.ON?GREEN:state.kind==DeviceControl.ERROR?RED:state.kind==DeviceControl.CONNECT?AMBER:MUTED);
        if(device.equals("pc"))cancel.setVisibility(state.shutdownPending?View.VISIBLE:View.GONE);
    }
    /** The home Wi-Fi and the phone's private IPv4 address on it, or null. */
    Pair<Network,LinkAddress> homeWifi(){
        ConnectivityManager cm=getSystemService(ConnectivityManager.class);
        for(Network candidate:cm.getAllNetworks()){
            NetworkCapabilities caps=cm.getNetworkCapabilities(candidate);LinkProperties links=cm.getLinkProperties(candidate);
            if(caps==null||links==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)||!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN))continue;
            for(LinkAddress link:links.getLinkAddresses())if(link.getAddress() instanceof Inet4Address&&link.getAddress().isSiteLocalAddress())return Pair.create(candidate,link);
        }
        return null;
    }
    String host(String device){return prefs.getString(device+"_host",device.equals("tv")?"192.168.1.6":"192.168.1.2");}
    static boolean lost(Exception e){return e instanceof ConnectException||e instanceof NoRouteToHostException||e instanceof SocketTimeoutException||e instanceof javax.net.ssl.SSLException;}
    /** Searches the Wi-Fi when the device stopped answering at lostHost; true when it now has another address. */
    boolean relocate(String device,boolean now,String lostHost){
        Object lock=searchLocks.get(device);if(lock==null)return false;
        synchronized(lock){
            Long last=searched.get(device);long t=SystemClock.elapsedRealtime();
            // Background polls search at most every 30 s, so a device that is just off does not sweep the network every 8 s.
            if(lostHost.equals(host(device))&&(last==null||t-last>(now?3000:30000))){
                searched.put(device,t);Pair<Network,LinkAddress> wifi=homeWifi();
                if(wifi!=null)try{DeviceControl.locate(prefs,device,wifi.second.getAddress(),wifi.second.getPrefixLength());}catch(Exception ignored){}
            }
            return !lostHost.equals(host(device));
        }
    }
    void wake(String device){
        String fallback=device.equals("tv")?"AA:BB:CC:DD:EE:01":"AA:BB:CC:DD:EE:02";
        work(()->{
            Pair<Network,LinkAddress> wifi=homeWifi();
            if(wifi==null)throw new Exception("Conecte o celular ao Wi-Fi de casa para enviar o comando de ligar.");
            InetAddress broadcast=LanWake.broadcast(wifi.second.getAddress(),wifi.second.getPrefixLength());
            try(DatagramSocket socket=new DatagramSocket()){wifi.first.bindSocket(socket);LanWake.send(socket,prefs.getString(device+"_mac",fallback),broadcast);}
            return "Comando de ligar enviado. Aguarde o aparelho iniciar.";
        },device,()->wake(device),()->{waking.put(device,SystemClock.elapsedRealtime()+25000);TextView t=device.equals("tv")?tvStatus:pcStatus;t.setText(R.string.waking);t.setTextColor(AMBER);});
    }
    void confirmTvOff(){new AlertDialog.Builder(this).setTitle("Desligar a TV?").setMessage("A reprodução será interrompida.").setNegativeButton("Voltar",null).setPositiveButton("Desligar",(d,w)->tv(true)).show();}
    void tv(boolean off){message.setText(R.string.tv_connecting);work(()->{try(DeviceControl.Tv connection=new DeviceControl.Tv(prefs)){if(off)connection.off();}return off?"Comando de desligar enviado à TV.":"TV conectada e autorizada.";},"tv",()->tv(off));}
    void confirmPcOff(){if(!prefs.contains("pc_token")){pairPc();return;}new AlertDialog.Builder(this).setTitle("Desligar o computador?").setMessage("Salve seus arquivos. Você terá 15 segundos para cancelar pelo app. Programas com trabalho não salvo podem impedir o desligamento.").setNegativeButton("Voltar",null).setPositiveButton("Desligar em 15 s",(d,w)->pcAction("/api/shutdown")).show();}
    void pcAction(String route){work(()->{JSONObject r=DeviceControl.pc(prefs,route,new JSONObject());boolean pending=r.optDouble("shutdown_at",0)>0;runOnUiThread(()->cancel.setVisibility(pending?View.VISIBLE:View.GONE));return r.optString("message","Comando recebido.");},"pc",()->pcAction(route));}
    void checkPc(){work(()->{JSONObject r=DeviceControl.pc(prefs,"/api/status",null);boolean pending=r.optDouble("shutdown_at",0)>0;runOnUiThread(()->cancel.setVisibility(pending?View.VISIBLE:View.GONE));return pending?"PC ligado · desligamento agendado":"PC ligado e conectado.";},"pc",()->checkPc());}
    void openAnyDeskPc(){
        if(!prefs.contains("pc_token")){pairPc();return;}
        work(()->{
            JSONObject result=DeviceControl.pc(prefs,"/api/anydesk/open",new JSONObject());
            return result.optBoolean("opened")?"AnyDesk aberto no PC.":result.optString("message","Comando recebido.");
        },"pc",()->openAnyDeskPc());
    }
    void pairPc(){LinearLayout box=vertical();box.setPadding(dp(24),dp(8),dp(24),0);box.addView(text("Digite o código de 6 números mostrado no PC pelo instalador do Casa.",16,INK));EditText code=new EditText(this);code.setHint("Código de pareamento");code.setInputType(InputType.TYPE_CLASS_NUMBER);code.setSingleLine(true);box.addView(code);new AlertDialog.Builder(this).setTitle("Conectar computador").setView(box).setNegativeButton("Voltar",null).setPositiveButton("Conectar",(d,w)->{String value=code.getText().toString().trim();if(!value.matches("[0-9]{6}")){message.setText(R.string.pair_code_invalid);return;}pairPcCode(value);}).show();}
    void pairPcCode(String code){work(()->{JSONObject r=DeviceControl.pc(prefs,"/pair",new JSONObject().put("code",code));String token=r.getString("token");prefs.edit().putString("pc_token",token).apply();return "Computador conectado. Agora você pode desligá-lo pelo app.";},"pc",()->pairPcCode(code));}
    void work(Work job,String device,Runnable retry){work(job,device,retry,null);}
    void work(Work job,String device,Runnable retry,Runnable success){
        if(busy.contains(device))return;busy.add(device);generation++;updateButtons();
        worker.submit(()->{String result=null;Exception failure=null;String before=host(device);try{result=job.run();}catch(Exception e){failure=e;}
            if(failure!=null&&lost(failure)&&relocate(device,true,before))try{result=job.run();failure=null;}catch(Exception e){failure=e;}
            final String done=result;final Exception error=failure;
            handler.post(()->{
                if(isFinishing()||isDestroyed())return;
                busy.remove(device);generation++;updateButtons();
                if(error instanceof DeviceControl.PairingNeeded){
                    String fp=((DeviceControl.PairingNeeded)error).fingerprint;
                    String host=prefs.getString(device+"_host",device.equals("tv")?"192.168.1.6":"192.168.1.2");
                    new AlertDialog.Builder(this).setTitle("Confiar neste dispositivo?")
                        .setMessage("Confirme que este é o endereço da sua "+(device.equals("tv")?"TV":"máquina")+": "+host+".\n\nIdentificação do certificado:\n"+fp+"\n\nO app vai reconhecer este certificado nas próximas conexões.")
                        .setNegativeButton("Voltar",null).setPositiveButton("Confiar e continuar",(d,w)->{prefs.edit().putString(device+"_pin",fp).apply();retry.run();}).show();pollNow();return;
                }
                if(error!=null){String msg=error.getMessage();message.setText(getString(R.string.connection_error,msg==null?error.getClass().getSimpleName():msg));pollNow();return;}
                message.setText(done);if(success!=null)success.run();
                pcConnect.setText(prefs.contains("pc_token")?R.string.pc_check:R.string.pc_connect);pollNow();
            });
        });
    }
    void settings(){LinearLayout box=vertical();box.setPadding(dp(22),dp(6),dp(22),0);LinkedHashMap<String,EditText> fields=new LinkedHashMap<>();String[][] rows={{"tv_host","IP da TV","192.168.1.6"},{"tv_mac","MAC Wi-Fi da TV","AA:BB:CC:DD:EE:01"},{"pc_host","IP do computador","192.168.1.2"},{"pc_mac","MAC do computador","AA:BB:CC:DD:EE:02"}};for(String[] row:rows){box.addView(text(row[1],14,MUTED));EditText e=new EditText(this);e.setText(prefs.getString(row[0],row[2]));e.setSingleLine(true);box.addView(e);fields.put(row[0],e);}ScrollView scroll=new ScrollView(this);scroll.addView(box);AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Dispositivos da casa").setView(scroll).setNegativeButton("Voltar",null).setNeutralButton("Reconectar",(d,w)->{prefs.edit().remove("tv_pin").remove("tv_key").remove("tv_repair").remove("pc_pin").remove("pc_token").remove("pc_anydesk_id").apply();build();}).setPositiveButton("Salvar",null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{try{for(String key:Arrays.asList("tv_host","pc_host"))DeviceControl.localHost(fields.get(key).getText().toString().trim());for(String key:Arrays.asList("tv_mac","pc_mac"))if(!fields.get(key).getText().toString().trim().matches("(?i)([0-9a-f]{2}[:-]){5}[0-9a-f]{2}"))throw new Exception("Confira os endereços MAC.");SharedPreferences.Editor edit=prefs.edit();for(String[] row:rows)edit.putString(row[0],fields.get(row[0]).getText().toString().trim());edit.apply();dialog.dismiss();build();}catch(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}}));dialog.show();}
    @Override public void onDestroy(){handler.removeCallbacksAndMessages(null);worker.shutdownNow();statusWorker.shutdownNow();super.onDestroy();}
    static final class DeviceIcon extends View {
        final Paint paint=new Paint(3);final int kind;
        public DeviceIcon(Context c){this(c,0);}
        DeviceIcon(Context c,int k){super(c);kind=k;setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        protected void onDraw(Canvas c){super.onDraw(c);c.save();c.scale(getWidth()/48f,getHeight()/48f);paint.setColor(INK);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.4f);paint.setStrokeCap(Paint.Cap.ROUND);if(kind==0){c.drawRoundRect(4,8,44,34,4,4,paint);c.drawLine(16,41,32,41,paint);c.drawLine(24,34,24,41,paint);}else if(kind==1){c.drawRoundRect(5,6,34,29,3,3,paint);c.drawLine(19,29,19,37,paint);c.drawLine(12,38,26,38,paint);c.drawRoundRect(38,14,46,40,2,2,paint);c.drawCircle(42,20,1,paint);}else{c.drawRoundRect(3,10,45,30,4,4,paint);c.drawLine(8,23,40,23,paint);for(int x=12;x<=36;x+=12)c.drawLine(x,35,x,42,paint);}c.restore();}
    }
}
