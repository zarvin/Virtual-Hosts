package com.github.xfalcon.vhosts.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import com.github.xfalcon.vhosts.R;
import com.github.xfalcon.vhosts.model.HostProfile;
import com.github.xfalcon.vhosts.data.HostProfileRepository;
import com.github.xfalcon.vhosts.data.HostsLoader;
import com.github.xfalcon.vhosts.util.LogUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class HostListAdapter extends RecyclerView.Adapter<HostListAdapter.ViewHolder> {
    private static final String TAG = "HostListAdapter";

    private List<HostProfile> profiles = new ArrayList<>();
    private Context context;
    private HostProfileRepository repo;
    private OnProfileClickListener listener;
    private OnStartDragListener dragListener;

    public interface OnProfileClickListener {
        void onProfileClick(HostProfile profile);
        void onProfileLongClick(HostProfile profile);
    }

    // 拖动手柄按下时通知 Activity 启动拖拽
    public interface OnStartDragListener {
        void onStartDrag(RecyclerView.ViewHolder vh);
    }

    public HostListAdapter(Context context, File profilesDir) {
        this.context = context;
        this.repo = new HostProfileRepository(profilesDir);
        this.profiles = repo.findAll();
    }

    public void setOnProfileClickListener(OnProfileClickListener listener) {
        this.listener = listener;
    }

    public void setOnStartDragListener(OnStartDragListener l) {
        this.dragListener = l;
    }

    public void setProfiles(List<HostProfile> newProfiles) {
        this.profiles = newProfiles;
        notifyDataSetChanged();
    }

    // 当前（可能被拖拽重排后的）顺序，供保存 order 用
    public List<HostProfile> getProfiles() {
        return profiles;
    }

    public void onItemMove(int from, int to) {
        if (from < 0 || to < 0 || from >= profiles.size() || to >= profiles.size()) return;
        Collections.swap(profiles, from, to);
        notifyItemMoved(from, to);
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        android.view.View v = LayoutInflater.from(context)
            .inflate(R.layout.item_host_profile, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(final ViewHolder holder, int position) {
        final HostProfile profile = profiles.get(position);
        holder.titleView.setText(profile.getTitle());
        // 先解绑再 setChecked，避免 RecyclerView 复用 ViewHolder 时误触发上一行的监听器
        holder.switchView.setOnCheckedChangeListener(null);
        holder.switchView.setChecked(profile.isEnabled());

        // 点击条目进编辑页
        holder.itemView.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                if (listener != null) {
                    listener.onProfileClick(profile);
                }
            }
        });
        // 长按弹出重命名 / 删除 / 刷新
        holder.itemView.setOnLongClickListener(new android.view.View.OnLongClickListener() {
            @Override
            public boolean onLongClick(android.view.View v) {
                if (listener != null) {
                    listener.onProfileLongClick(profile);
                    return true;
                }
                return false;
            }
        });

        // 按住拖动手柄启动拖拽排序
        holder.dragHandle.setOnTouchListener(new android.view.View.OnTouchListener() {
            @Override
            public boolean onTouch(android.view.View v, MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN && dragListener != null) {
                    dragListener.onStartDrag(holder);
                }
                return false;
            }
        });

        // 开关变化触发更新和重载
        holder.switchView.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                int pos = holder.getBindingAdapterPosition();
                if (pos == RecyclerView.NO_POSITION || pos >= profiles.size()) return;
                try {
                    HostProfile updated = profiles.get(pos).withEnabled(isChecked);
                    // 同步内存：否则随后拖拽的 persistOrder 会用陈旧对象 withOrder 覆盖回旧 enabled
                    profiles.set(pos, updated);
                    repo.updateMeta(updated);
                    HostsLoader.reloadIfRunning(context);
                    LogUtils.d(TAG, "Profile " + updated.getId() + " enabled=" + isChecked);
                } catch (Exception e) {
                    LogUtils.e(TAG, "Error updating profile", e);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return profiles.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView titleView;
        Switch switchView;
        ImageView dragHandle;

        ViewHolder(android.view.View itemView) {
            super(itemView);
            titleView = itemView.findViewById(R.id.profile_title);
            switchView = itemView.findViewById(R.id.profile_switch);
            dragHandle = itemView.findViewById(R.id.drag_handle);
        }
    }
}
