package com.example.chathub.adapter;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.chathub.R;
import com.example.chathub.util.AvatarUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * MemberAdapter —「在線成員清單」轉接器（新建）。
 *
 * 解決什麼問題：
 *   把伺服器推來的成員名稱清單顯示成一列列「頭像 + 暱稱 + 在線綠點」。
 *   每次收到新的 ROSTER 就整批替換（成員不多，直接 setMembers + notifyDataSetChanged 最單純）。
 */
public class MemberAdapter extends RecyclerView.Adapter<MemberAdapter.VH> {

    private final List<String> members = new ArrayList<>();

    /** 用最新成員清單整批替換並刷新（ROSTER 是全量推送，直接覆蓋即可）。 */
    public void setMembers(List<String> names) {
        members.clear();
        members.addAll(names);
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return members.size();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_member, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        String name = members.get(position);
        holder.name.setText(name);
        // 頭像：首字 + 依暱稱上色（與訊息泡泡同一套規則，視覺一致）。
        holder.avatar.setText(AvatarUtils.initialOf(name));
        holder.avatar.setBackgroundTintList(
                ColorStateList.valueOf(AvatarUtils.colorFor(name)));
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView avatar, name;
        VH(View v) {
            super(v);
            avatar = v.findViewById(R.id.tvMemberAvatar);
            name = v.findViewById(R.id.tvMemberName);
        }
    }
}
