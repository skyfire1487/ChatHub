package com.example.chathub.adapter;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.chathub.R;
import com.example.chathub.model.ChatMessage;
import com.example.chathub.util.AvatarUtils;
import com.example.chathub.util.TimeFormat;

import java.util.ArrayList;
import java.util.List;

/**
 * MessageAdapter —「聊天訊息清單」轉接器（新建）。
 *
 * 解決什麼問題：
 *   把一串 ChatMessage 餵給 RecyclerView 顯示，並依每則的型別（自己/他人/系統）套用不同版型。
 *   這是「多 view type」的標準寫法：getItemViewType 回不同型別 → onCreateViewHolder 充氣對應 layout。
 *
 * 為何把三種泡泡拆成三個 ViewHolder：
 *   三種版型差異大（靠左/靠右/置中、有無頭像與暱稱），各自一個 VH 最清楚，
 *   onBindViewHolder 依型別分派，避免一個 VH 塞滿 if 判斷。
 */
public class MessageAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    // 三種 view type 的代號。
    private static final int T_SELF = 0;
    private static final int T_OTHER = 1;
    private static final int T_SYSTEM = 2;

    private final List<ChatMessage> items = new ArrayList<>();

    /** 新增一則訊息到尾端，並通知 RecyclerView 只刷新這一筆（比 notifyDataSetChanged 高效）。 */
    public void add(ChatMessage message) {
        items.add(message);
        notifyItemInserted(items.size() - 1);
    }

    /** 目前訊息數（ChatActivity 用來把清單捲到最底）。 */
    public int size() {
        return items.size();
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    /** 依該則訊息的 Kind 回傳對應 view type，RecyclerView 才知道要用哪種版型。 */
    @Override
    public int getItemViewType(int position) {
        switch (items.get(position).getKind()) {
            case SELF:   return T_SELF;
            case SYSTEM: return T_SYSTEM;
            default:     return T_OTHER;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case T_SELF:
                return new SelfVH(inflater.inflate(R.layout.item_message_self, parent, false));
            case T_SYSTEM:
                return new SystemVH(inflater.inflate(R.layout.item_message_system, parent, false));
            default:
                return new OtherVH(inflater.inflate(R.layout.item_message_other, parent, false));
        }
    }

    /** 依 ViewHolder 實際型別，把資料綁上去。 */
    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        ChatMessage m = items.get(position);
        if (holder instanceof SelfVH) {
            ((SelfVH) holder).bind(m);
        } else if (holder instanceof OtherVH) {
            ((OtherVH) holder).bind(m);
        } else if (holder instanceof SystemVH) {
            ((SystemVH) holder).bind(m);
        }
    }

    // ── 三種 ViewHolder ───────────────────────────────────────────────────

    /** 自己的訊息：只有內文與時間。 */
    static class SelfVH extends RecyclerView.ViewHolder {
        final TextView body, time;
        SelfVH(View v) {
            super(v);
            body = v.findViewById(R.id.tvBody);
            time = v.findViewById(R.id.tvTime);
        }
        void bind(ChatMessage m) {
            body.setText(m.getBody());
            time.setText(TimeFormat.hhmm(m.getTimestamp()));
        }
    }

    /** 他人的訊息：頭像(首字+依暱稱上色) + 暱稱 + 內文 + 時間。 */
    static class OtherVH extends RecyclerView.ViewHolder {
        final TextView avatar, name, body, time;
        OtherVH(View v) {
            super(v);
            avatar = v.findViewById(R.id.tvAvatar);
            name = v.findViewById(R.id.tvName);
            body = v.findViewById(R.id.tvBody);
            time = v.findViewById(R.id.tvTime);
        }
        void bind(ChatMessage m) {
            name.setText(m.getName());
            body.setText(m.getBody());
            time.setText(TimeFormat.hhmm(m.getTimestamp()));
            // 頭像：首字當文字、底圓顏色依暱稱雜湊決定（同一人到處同色）。
            avatar.setText(AvatarUtils.initialOf(m.getName()));
            avatar.setBackgroundTintList(
                    ColorStateList.valueOf(AvatarUtils.colorFor(m.getName())));
        }
    }

    /** 系統訊息：置中膠囊文字。 */
    static class SystemVH extends RecyclerView.ViewHolder {
        final TextView text;
        SystemVH(View v) {
            super(v);
            text = v.findViewById(R.id.tvSystem);
        }
        void bind(ChatMessage m) {
            text.setText(m.getBody());
        }
    }
}
