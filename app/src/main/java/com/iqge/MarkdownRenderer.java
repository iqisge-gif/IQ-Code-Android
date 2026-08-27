package com.iqge;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.view.Gravity;
import android.view.View;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compact native Markdown renderer modeled after IQ Code's text-first transcript. */
public final class MarkdownRenderer {
    /** Immutable renderer palette: no process-global theme mutation while a transcript is rebuilding. */
    private static final class Palette {
        final int text,plain,muted,codeBg,codeBgEnd,codeMeta,codeBorder,accent;
        final int keyword,string,number,comment,type,add,delete,addBg,deleteBg,inlineCode,inlineBg;
        Palette(int text,int plain,int muted,int codeBg,int codeBgEnd,int codeMeta,int codeBorder,int accent,
                int keyword,int string,int number,int comment,int type,int add,int delete,int addBg,int deleteBg,
                int inlineCode,int inlineBg){
            this.text=text;this.plain=plain;this.muted=muted;this.codeBg=codeBg;this.codeBgEnd=codeBgEnd;
            this.codeMeta=codeMeta;this.codeBorder=codeBorder;this.accent=accent;this.keyword=keyword;
            this.string=string;this.number=number;this.comment=comment;this.type=type;this.add=add;
            this.delete=delete;this.addBg=addBg;this.deleteBg=deleteBg;this.inlineCode=inlineCode;this.inlineBg=inlineBg;
        }
    }
    private static final Palette CLASSIC=new Palette(
            Color.rgb(238,234,228),Color.rgb(205,202,197),Color.rgb(150,145,138),
            Color.rgb(20,20,19),Color.rgb(20,20,19),Color.rgb(110,106,101),Color.TRANSPARENT,Color.rgb(217,119,87),
            Color.rgb(202,158,242),Color.rgb(155,199,139),Color.rgb(225,174,105),Color.rgb(111,117,120),Color.rgb(117,180,228),
            Color.rgb(126,178,136),Color.rgb(214,120,111),Color.argb(28,126,178,136),Color.argb(28,214,120,111),
            Color.rgb(231,184,145),Color.argb(38,255,255,255));
    private static final Palette NEON=new Palette(
            Color.rgb(244,243,255),Color.rgb(216,219,238),Color.rgb(142,149,179),
            Color.rgb(5,11,25),Color.rgb(18,24,52),Color.rgb(125,134,171),Color.rgb(72,70,139),Color.rgb(142,78,255),
            Color.rgb(190,159,255),Color.rgb(112,214,179),Color.rgb(244,184,106),Color.rgb(105,115,143),Color.rgb(101,199,255),
            Color.rgb(28,190,145),Color.rgb(255,111,132),Color.argb(48,28,190,145),Color.argb(48,255,111,132),
            Color.rgb(220,204,255),Color.argb(60,125,96,255));
    private static final Palette DAY=new Palette(
            Color.rgb(29,36,51),Color.rgb(61,70,85),Color.rgb(103,115,133),
            Color.rgb(242,245,250),Color.rgb(232,237,245),Color.rgb(99,112,132),Color.rgb(207,215,226),Color.rgb(83,91,214),
            Color.rgb(109,40,217),Color.rgb(11,122,90),Color.rgb(180,83,9),Color.rgb(107,120,136),Color.rgb(29,78,216),
            Color.rgb(22,115,76),Color.rgb(180,35,46),Color.argb(34,22,115,76),Color.argb(34,180,35,46),
            Color.rgb(154,52,18),Color.argb(42,83,91,214));
    private MarkdownRenderer(){}

    public static LinearLayout render(Context c,String markdown,float textSp){
        return render(c,markdown,textSp,false,false);
    }

    public static LinearLayout render(Context c,String markdown,float textSp,boolean neonTheme){
        return render(c,markdown,textSp,neonTheme,false);
    }

    public static LinearLayout render(Context c,String markdown,float textSp,boolean neonTheme,boolean lightTheme){
        Palette palette=lightTheme?DAY:neonTheme?NEON:CLASSIC;
        LinearLayout root=new LinearLayout(c);root.setOrientation(LinearLayout.VERTICAL);
        if(markdown==null)markdown="";
        String[] lines=markdown.replace("\r\n","\n").split("\n",-1);
        StringBuilder prose=new StringBuilder();
        for(int i=0;i<lines.length;i++){
            String line=lines[i];
            if(line.startsWith("```")){
                flushProse(c,root,prose,textSp,palette);
                String lang=line.substring(3).trim();StringBuilder code=new StringBuilder();i++;
                while(i<lines.length&&!lines[i].startsWith("```")){if(code.length()>0)code.append('\n');code.append(lines[i]);i++;}
                root.addView(codeBlock(c,lang,code.toString(),palette,neonTheme),new LinearLayout.LayoutParams(-1,-2));
            }else{if(prose.length()>0)prose.append('\n');prose.append(line);}
        }
        flushProse(c,root,prose,textSp,palette);return root;
    }

    private static void flushProse(Context c,LinearLayout root,StringBuilder prose,float textSp,Palette palette){
        if(prose.length()==0)return;String raw=prose.toString();prose.setLength(0);
        String[] chunks=raw.split("\\n\\s*\\n");
        for(String chunk:chunks){
            if(chunk.trim().isEmpty())continue;
            TextView t=new TextView(c);t.setTextSize(textSp);t.setTextColor(palette.text);t.setTextIsSelectable(true);
            t.setLineSpacing(dp(c,1),1.10f);t.setPadding(0,dp(c,1),0,dp(c,7));t.setText(inline(chunk,palette));t.setLinksClickable(true);t.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
            root.addView(t,new LinearLayout.LayoutParams(-1,-2));
        }
    }

    /**
     * Parse the inline Markdown used by model replies into spans while removing
     * the Markdown delimiter characters themselves. The old renderer only put
     * a bold span over "**text**", which is why the two asterisks remained
     * visible in chat.
     *
     * This parser intentionally stays small and Android-native, but covers the
     * syntax IQ Code emits most often: code, bold, italic, strike-through and
     * links. Nested emphasis is supported recursively.
     */
    public static CharSequence inlineText(String src){ return inline(src,CLASSIC); }
    public static CharSequence inlineText(String src,boolean neonTheme){ return inlineText(src,neonTheme,false); }
    public static CharSequence inlineText(String src,boolean neonTheme,boolean lightTheme){ return inline(src,lightTheme?DAY:neonTheme?NEON:CLASSIC); }

    private static CharSequence inline(String src,Palette palette){
        SpannableStringBuilder out=new SpannableStringBuilder();
        appendInline(out,src==null?"":src,0,(src==null?0:src.length()),palette,0);
        return out;
    }

    private static void appendInline(SpannableStringBuilder out,String src,int from,int to,Palette palette,int depth){
        if(depth>=24){out.append(src,from,to);return;}
        int i=from;
        while(i<to){
            // Backslash escaping: \* \_ \` \~ \[ and \\.
            if(src.charAt(i)=='\\'&&i+1<to&&"*_`~[\\".indexOf(src.charAt(i+1))>=0){
                out.append(src.charAt(i+1));i+=2;continue;
            }

            // Inline code has the highest priority. Markdown inside code is
            // deliberately not parsed.
            if(src.charAt(i)=='`'){
                int close=src.indexOf('`',i+1);
                if(close>i+1&&close<to){
                    int st=out.length();out.append(src,i+1,close);int en=out.length();
                    out.setSpan(new ForegroundColorSpan(palette.inlineCode),st,en,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new TypefaceSpan("monospace"),st,en,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    out.setSpan(new BackgroundColorSpan(palette.inlineBg),st,en,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i=close+1;continue;
                }
            }

            // Links: [label](https://...). Keep only the label on screen.
            if(src.charAt(i)=='['){
                int mid=src.indexOf("](",i+1);
                if(mid>i+1&&mid<to){
                    int close=src.indexOf(')',mid+2);
                    if(close>mid+2&&close<to){
                        String url=src.substring(mid+2,close).trim();
                        int st=out.length();appendInline(out,src,i+1,mid,palette,depth+1);int en=out.length();
                        if(en>st){
                            out.setSpan(new ForegroundColorSpan(palette.type),st,en,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                            out.setSpan(new URLSpan(url),st,en,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        }
                        i=close+1;continue;
                    }
                }
            }

            // Bold: **text** and __text__. Remove both delimiter pairs.
            if(i+1<to&&((src.charAt(i)=='*'&&src.charAt(i+1)=='*')||(src.charAt(i)=='_'&&src.charAt(i+1)=='_'))){
                String token=src.substring(i,i+2);int close=src.indexOf(token,i+2);
                if(close>i+2&&close<to){
                    int st=out.length();appendInline(out,src,i+2,close,palette,depth+1);int en=out.length();
                    if(en>st)out.setSpan(new StyleSpan(Typeface.BOLD),st,en,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i=close+2;continue;
                }
            }

            // Strike-through: ~~text~~.
            if(i+1<to&&src.charAt(i)=='~'&&src.charAt(i+1)=='~'){
                int close=src.indexOf("~~",i+2);
                if(close>i+2&&close<to){
                    int st=out.length();appendInline(out,src,i+2,close,palette,depth+1);int en=out.length();
                    if(en>st)out.setSpan(new StrikethroughSpan(),st,en,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i=close+2;continue;
                }
            }

            // Italic: *text* and _text_. Don't treat ** / __ as italic.
            char ch=src.charAt(i);
            if((ch=='*'||ch=='_')&&(i+1>=to||src.charAt(i+1)!=ch)){
                int close=findSingleDelimiter(src,ch,i+1,to);
                if(close>i+1){
                    int st=out.length();appendInline(out,src,i+1,close,palette,depth+1);int en=out.length();
                    if(en>st)out.setSpan(new StyleSpan(Typeface.ITALIC),st,en,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    i=close+1;continue;
                }
            }

            out.append(src.charAt(i));i++;
        }
    }

    private static int findSingleDelimiter(String src,char delimiter,int from,int to){
        for(int i=from;i<to;i++){
            if(src.charAt(i)!=delimiter)continue;
            if(i>0&&src.charAt(i-1)=='\\')continue;
            if(i+1<to&&src.charAt(i+1)==delimiter)continue;
            return i;
        }
        return -1;
    }

    private static View codeBlock(Context c,String lang,String code,Palette palette,boolean neonTheme){
        String display=lang==null||lang.trim().isEmpty()?"text":lang.trim().toLowerCase(Locale.US);
        LinearLayout block=new LinearLayout(c);block.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg=neonTheme?new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{palette.codeBg,palette.codeBgEnd}):new GradientDrawable();
        if(!neonTheme)bg.setColor(palette.codeBg);bg.setCornerRadius(dp(c,12));if(neonTheme)bg.setStroke(dp(c,1),palette.codeBorder);block.setBackground(bg); block.setClipToOutline(true);

        LinearLayout meta=new LinearLayout(c);meta.setOrientation(LinearLayout.HORIZONTAL);meta.setGravity(Gravity.CENTER_VERTICAL);meta.setPadding(dp(c,10),0,dp(c,4),0);
        TextView name=new TextView(c);name.setText(display);name.setTextSize(9.5f);name.setTextColor(palette.codeMeta);name.setTypeface(Typeface.MONOSPACE);meta.addView(name,new LinearLayout.LayoutParams(0,dp(c,27),1));
        TextView copy=new TextView(c);copy.setText("copy");copy.setTextSize(9.5f);copy.setTextColor(palette.codeMeta);copy.setGravity(Gravity.CENTER);copy.setPadding(dp(c,8),0,dp(c,8),0);
        copy.setOnClickListener(v->{ClipboardManager cm=(ClipboardManager)c.getSystemService(Context.CLIPBOARD_SERVICE);cm.setPrimaryClip(ClipData.newPlainText("code",code));copy.setText("copied");UiMotion.contentUpdated(copy,true);copy.postDelayed(()->{copy.setText("copy");UiMotion.contentUpdated(copy,false);},520);});
        meta.addView(copy,new LinearLayout.LayoutParams(-2,dp(c,27)));block.addView(meta,new LinearLayout.LayoutParams(-1,dp(c,27)));

        HorizontalScrollView hs=new HorizontalScrollView(c);hs.setHorizontalScrollBarEnabled(false);hs.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        TextView body=new TextView(c);body.setText(highlight(code,display,palette));body.setTextColor(display.equals("text")?palette.plain:palette.text);body.setTextSize(10.8f);body.setTypeface(Typeface.MONOSPACE);body.setTextIsSelectable(true);body.setHorizontallyScrolling(true);body.setLineSpacing(0,1.06f);body.setPadding(dp(c,10),dp(c,4),dp(c,14),dp(c,10));
        hs.addView(body,new HorizontalScrollView.LayoutParams(-2,-2));block.addView(hs,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(c,4),0,dp(c,9));block.setLayoutParams(p);return block;
    }

    public static CharSequence highlight(String code,String lang){
        return highlight(code,lang,CLASSIC);
    }

    public static CharSequence highlight(String code,String lang,boolean neonTheme){
        return highlight(code,lang,neonTheme,false);
    }

    public static CharSequence highlight(String code,String lang,boolean neonTheme,boolean lightTheme){
        return highlight(code,lang,lightTheme?DAY:neonTheme?NEON:CLASSIC);
    }

    private static CharSequence highlight(String code,String lang,Palette palette){
        String raw=code==null?"":code;SpannableString s=new SpannableString(raw);String l=lang==null?"":lang.toLowerCase(Locale.US);
        if(l.equals("diff")||l.equals("patch")){
            int pos=0;for(String line:raw.split("\n",-1)){
                int end=Math.min(s.length(),pos+line.length());
                if(end>pos){
                    if(line.startsWith("+")&&!line.startsWith("+++")){s.setSpan(new ForegroundColorSpan(palette.add),pos,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);s.setSpan(new BackgroundColorSpan(palette.addBg),pos,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
                    else if(line.startsWith("-")&&!line.startsWith("---")){s.setSpan(new ForegroundColorSpan(palette.delete),pos,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);s.setSpan(new BackgroundColorSpan(palette.deleteBg),pos,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);}
                    else if(line.startsWith("@@"))s.setSpan(new ForegroundColorSpan(palette.accent),pos,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                pos+=line.length()+1;
            }return s;
        }
        List<SpanSpec> specs=new ArrayList<>();
        if(l.contains("java")||l.contains("kotlin")||l.equals("kt")||l.contains("javascript")||l.equals("js")||l.contains("typescript")||l.equals("ts")){
            add(specs,raw,"\\b(class|interface|enum|record|public|private|protected|static|final|void|int|long|double|float|boolean|new|return|if|else|for|while|switch|case|try|catch|finally|throw|throws|extends|implements|import|package|this|super|const|let|var|function|async|await|export|default|null|true|false)\\b",palette.keyword);
            add(specs,raw,"\\b(String|Object|Integer|Long|Boolean|List|Map|Set|File|JSONObject|JSONArray|Promise)\\b",palette.type);
            add(specs,raw,"\\b\\d+(?:\\.\\d+)?\\b",palette.number);add(specs,raw,"\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'",palette.string);add(specs,raw,"//.*$|/\\*[\\s\\S]*?\\*/",palette.comment,Pattern.MULTILINE);
        }else if(l.equals("json")){add(specs,raw,"\"(?:\\\\.|[^\"\\\\])*\"(?=\\s*:)",palette.type);add(specs,raw,"\"(?:\\\\.|[^\"\\\\])*\"",palette.string);add(specs,raw,"\\b(true|false|null)\\b",palette.keyword);add(specs,raw,"-?\\b\\d+(?:\\.\\d+)?\\b",palette.number);
        }else if(l.equals("bash")||l.equals("sh")||l.equals("shell")||l.equals("zsh")){add(specs,raw,"\\b(if|then|else|elif|fi|for|do|done|while|case|esac|function|in|export|local|readonly)\\b",palette.keyword);add(specs,raw,"\"(?:\\\\.|[^\"\\\\])*\"|'[^']*'",palette.string);add(specs,raw,"#.*$",palette.comment,Pattern.MULTILINE);add(specs,raw,"\\$\\{?[A-Za-z_][A-Za-z0-9_]*\\}?",palette.type);
        }else if(l.equals("python")||l.equals("py")){add(specs,raw,"\\b(def|class|import|from|as|if|elif|else|for|while|try|except|finally|with|return|yield|async|await|lambda|True|False|None|and|or|not|in|is)\\b",palette.keyword);add(specs,raw,"\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''|\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'",palette.string);add(specs,raw,"#.*$",palette.comment,Pattern.MULTILINE);add(specs,raw,"\\b\\d+(?:\\.\\d+)?\\b",palette.number);
        }else if(l.equals("yaml")||l.equals("yml")){add(specs,raw,"^[ \\t-]*[A-Za-z0-9_.-]+(?=\\s*:)",palette.type,Pattern.MULTILINE);add(specs,raw,"#.*$",palette.comment,Pattern.MULTILINE);add(specs,raw,"\\b(true|false|null)\\b",palette.keyword);}
        else if(l.equals("markdown")||l.equals("md")){add(specs,raw,"^#{1,6}\\s+.*$",palette.type,Pattern.MULTILINE);add(specs,raw,"`[^`]+`",palette.string);add(specs,raw,"\\*\\*[^*]+\\*\\*|__[^_]+__",palette.keyword);add(specs,raw,"\\[[^\\]]+\\]\\([^\\)]+\\)",palette.accent);add(specs,raw,"^(---|\\.\\.\\.)$",palette.comment,Pattern.MULTILINE);add(specs,raw,"^[A-Za-z0-9_.-]+(?=\\s*:)",palette.type,Pattern.MULTILINE);}
        for(SpanSpec x:specs)if(x.start>=0&&x.end<=s.length()&&x.end>x.start)s.setSpan(new ForegroundColorSpan(x.color),x.start,x.end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);return s;
    }
    private static void add(List<SpanSpec> out,String s,String regex,int color){add(out,s,regex,color,0);}private static void add(List<SpanSpec> out,String s,String regex,int color,int flags){try{Matcher m=Pattern.compile(regex,flags).matcher(s);while(m.find())out.add(new SpanSpec(m.start(),m.end(),color));}catch(Exception ignored){}}
    private static final class SpanSpec{final int start,end,color;SpanSpec(int s,int e,int c){start=s;end=e;color=c;}}
    private static int dp(Context c,int v){return(int)(v*c.getResources().getDisplayMetrics().density+0.5f);}
}
