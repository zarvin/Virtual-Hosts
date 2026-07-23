package com.github.xfalcon.vhosts.ui;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.CompoundButton;
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
import java.util.List;

public class HostListAdapter extends RecyclerView.Adapter<HostListAdapter.ViewHolder> {
    private static final String TAG = "HostListAdapter";

    private List<HostProfile> profiles = new ArrayList<>();
    private Context context;
    private HostProfileRepository repo;
    private OnProfileClickListener listener;

    public interface OnProfileClickListener {
        void onProfileClick(HostProfile profile);
    }

    public HostListAdapter(Context context, File profilesDir) {
        this.context = context;
        this.repo = new HostProfileRepository(profilesDir);
        this.profiles = repo.findAll();
    }

    public void setOnProfileClickListener(OnProfileClickListener listener) {
        this.listener = listener;
    }

    public void setProfiles(List<HostProfile> newProfiles) {
        this.profiles = newProfiles;
        notifyDataSetChanged();
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        android.view.View v = LayoutInflater.from(context)
            .inflate(R.layout.item_host_profile, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
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

        // 开关变化触发更新和重载
        holder.switchView.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                try {
                    HostProfile updated = profile.withEnabled(isChecked);
                    repo.updateMeta(updated);
                    HostsLoader.reloadIfRunning(context);
                    LogUtils.d(TAG, "Profile " + profile.getId() + " enabled=" + isChecked);
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

        ViewHolder(android.view.View itemView) {
            super(itemView);
            titleView = itemView.findViewById(R.id.profile_title);
            switchView = itemView.findViewById(R.id.profile_switch);
        }
    }
}
