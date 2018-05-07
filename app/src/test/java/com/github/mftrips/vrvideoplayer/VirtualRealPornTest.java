package com.github.mftrips.vrvideoplayer;

import android.support.v4.util.SparseArrayCompat;

import org.junit.Assert;
import org.junit.Test;
import org.metafetish.buttplug.core.ButtplugDeviceMessage;
import org.metafetish.buttplug.core.Messages.FleshlightLaunchFW12Cmd;
import org.metafetish.buttplug.core.Messages.SingleMotorVibrateCmd;
import org.metafetish.buttplug.core.Messages.VorzeA10CycloneCmd;
import org.metafetish.haptic_file_reader.Commands.FunscriptCommand;
import org.metafetish.haptic_file_reader.Commands.HapticCommand;
import org.metafetish.haptic_file_reader.Commands.KiirooCommand;
import org.metafetish.haptic_file_reader.Handlers.FunscriptHandler;
import org.metafetish.haptic_file_reader.Handlers.VirtualRealPornHandler;
import org.metafetish.haptic_file_reader.HapticDevice;
import org.metafetish.haptic_file_reader.HapticFileHandler;
import org.metafetish.haptic_file_reader.Properties.FunscriptProperties;
import org.metafetish.haptic_file_reader.Properties.HapticProperties;
import org.metafetish.haptic_file_reader.Properties.VirtualRealPornProperties;

import java.util.List;

public class VirtualRealPornTest {
    private static String sample = "" +
            "[Player]\n" +
            "zoom=0\n" +
            "vert_rot=1.5\n" +
            "h_offset=0\n" +
            "\n" +
            "[VideoInfo]\n" +
            "name=Video Name\n" +
            "version=2\n" +
            "\n" +
            "[Kiiroo]\n" +
            "onyx=1,3;2.5,4;3.1,0\n" +
            "\n" +
            "[Lovense]\n" +
            "hombre=-1/06-2.6/00-3.2/06";

    @Test
    public void test() {
        HapticFileHandler handler = HapticFileHandler.handleString(VirtualRealPornTest.sample);
        Assert.assertNotNull(handler);
        Assert.assertEquals(VirtualRealPornHandler.class, handler.getClass());
        HapticProperties hapticProperties = handler.getProperties();
        Assert.assertNotNull(hapticProperties);
        Assert.assertEquals(VirtualRealPornProperties.class, hapticProperties.getClass());
        VirtualRealPornProperties properties = (VirtualRealPornProperties) hapticProperties;
        VirtualRealPornProperties.Player player = properties.getPlayer();
        Assert.assertNotNull(player);
        Assert.assertEquals(0, player.getZoom());
        Assert.assertEquals(1.5, player.getVertRot(), 0);
        Assert.assertEquals(0, player.getHOffset());
        VirtualRealPornProperties.VideoInfo videoInfo = properties.getVideoInfo();
        Assert.assertNotNull(videoInfo);
        Assert.assertEquals("Video Name", videoInfo.getName());
        Assert.assertEquals(2, videoInfo.getVersion());
        List<HapticCommand> hapticCommands = handler.getCommands();
        Assert.assertNotNull(hapticCommands);
        Assert.assertEquals(6, hapticCommands.size());
        for (HapticCommand command : hapticCommands) {
            Assert.assertEquals(KiirooCommand.class, command.getClass());
        }
        for (int i = 0; i < 3; ++i) {
            Assert.assertEquals(HapticDevice.LINEAR, hapticCommands.get(i).getDevice());
        }
        for (int i = 3; i < 6; ++i) {
            Assert.assertEquals(HapticDevice.VIBRATE, hapticCommands.get(i).getDevice());
        }
        Assert.assertEquals(1000, hapticCommands.get(0).getTime());
        Assert.assertEquals(3, ((KiirooCommand) hapticCommands.get(0)).getPosition());
        Assert.assertEquals(2500, hapticCommands.get(1).getTime());
        Assert.assertEquals(4, ((KiirooCommand) hapticCommands.get(1)).getPosition());
        Assert.assertEquals(3100, hapticCommands.get(2).getTime());
        Assert.assertEquals(0, ((KiirooCommand) hapticCommands.get(2)).getPosition());
        Assert.assertEquals(1000, hapticCommands.get(3).getTime());
        Assert.assertEquals(6, ((KiirooCommand) hapticCommands.get(3)).getPosition());
        Assert.assertEquals(2600, hapticCommands.get(4).getTime());
        Assert.assertEquals(0, ((KiirooCommand) hapticCommands.get(4)).getPosition());
        Assert.assertEquals(3200, hapticCommands.get(5).getTime());
        Assert.assertEquals(6, ((KiirooCommand) hapticCommands.get(5)).getPosition());

        SparseArrayCompat<List<ButtplugDeviceMessage>> buttplugCommands = HapticCommandToButtplugMessage.hapticCommandToButtplugMessage(hapticCommands, hapticProperties);
        for (int i = 0; i < buttplugCommands.size(); ++i) {
            int time = buttplugCommands.keyAt(i);
            System.out.println(time);
            List<ButtplugDeviceMessage> buttplugMessages = buttplugCommands.valueAt(i);
            for (ButtplugDeviceMessage buttplugMessage : buttplugMessages) {
                if (buttplugMessage instanceof FleshlightLaunchFW12Cmd) {
                    System.out.println(String.format("%s: %s, %s", buttplugMessage.getClass().getSimpleName(),
                            ((FleshlightLaunchFW12Cmd) buttplugMessage).getSpeed(), ((FleshlightLaunchFW12Cmd) buttplugMessage).getPosition()));
                } else if (buttplugMessage instanceof SingleMotorVibrateCmd) {
                    System.out.println(String.format("%s: %s", buttplugMessage.getClass().getSimpleName(), ((SingleMotorVibrateCmd) buttplugMessage).getSpeed()));
                } else if (buttplugMessage instanceof VorzeA10CycloneCmd){
                    System.out.println(String.format("%s: %s, %s", buttplugMessage.getClass().getSimpleName(),
                            ((VorzeA10CycloneCmd) buttplugMessage).getSpeed(), ((VorzeA10CycloneCmd) buttplugMessage).clockwise));
                }
            }
        }

        Assert.assertEquals(37, buttplugCommands.size());
    }
}
