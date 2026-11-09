package com.yuelengm.nhkantan.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import net.minecraft.item.ItemStack;

import com.yuelengm.nhkantan.NHKantan;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Optional client-only bridge to the same queue used by GTNH AE crafting-completion notifications. */
@SideOnly(Side.CLIENT)
final class AeMarketNotifications {

    private static boolean initialized;
    private static boolean available;
    private static Method createStack;
    private static Method getGui;
    private static Method queue;
    private static Constructor<?> notification;

    private AeMarketNotifications() {}

    static boolean show(ItemStack icon, String title, String description) {
        try {
            if (!initialized) {
                initialized = true;
                if (!Loader.isModLoaded("appliedenergistics2")) return false;
                Class<?> stackType = Class.forName("appeng.api.storage.data.IAEStack");
                Class<?> noticeType = Class.forName("appeng.client.render.notification.Notification");
                Class<?> manager = Class.forName("appeng.client.render.notification.NotificationManager");
                createStack = Class.forName("appeng.util.item.AEItemStack")
                    .getMethod("create", ItemStack.class);
                notification = noticeType.getConstructor(stackType, String.class, String.class);
                getGui = manager.getMethod("getGuiNotification");
                queue = Class.forName("appeng.client.render.notification.GuiNotification")
                    .getMethod("queueNotification", noticeType);
                available = true;
            }
            if (!available) return false;
            Object notice = notification.newInstance(createStack.invoke(null, icon), title, description);
            queue.invoke(getGui.invoke(null), notice);
            return true;
        } catch (ReflectiveOperationException | LinkageError error) {
            available = false;
            NHKantan.LOG.warn("GTNH AE notification queue unavailable; market notices will use chat and sound", error);
            return false;
        }
    }
}
