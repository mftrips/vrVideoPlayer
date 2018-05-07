package com.github.mftrips.vrvideoplayer;

import android.support.v4.util.SparseArrayCompat;
import android.util.SparseArray;

import org.junit.Assert;
import org.junit.Test;
import org.metafetish.buttplug.core.ButtplugDeviceMessage;
import org.metafetish.buttplug.core.Messages.FleshlightLaunchFW12Cmd;
import org.metafetish.buttplug.core.Messages.SingleMotorVibrateCmd;
import org.metafetish.buttplug.core.Messages.VorzeA10CycloneCmd;
import org.metafetish.haptic_file_reader.Commands.FunscriptCommand;
import org.metafetish.haptic_file_reader.Commands.HapticCommand;
import org.metafetish.haptic_file_reader.Handlers.FunscriptHandler;
import org.metafetish.haptic_file_reader.HapticFileHandler;
import org.metafetish.haptic_file_reader.Properties.FunscriptProperties;
import org.metafetish.haptic_file_reader.Properties.HapticProperties;

import java.util.List;

public class FunscriptTest {
    private static String sample = "{" +
            "\"version\": \"1.0\", " +
            "\"range\": 90, " +
            "\"inverted\": false, " +
            "\"actions\": [" +
            "{\"pos\": 75, \"at\": 1000}, " +
            "{\"pos\": 100, \"at\": 2500}, " +
            "{\"pos\": 0, \"at\": 3100}" +
            "]}";

    @Test
    public void test() {
        HapticFileHandler handler = HapticFileHandler.handleString(FunscriptTest.sample);
        Assert.assertNotNull(handler);
        Assert.assertEquals(FunscriptHandler.class, handler.getClass());
        HapticProperties hapticProperties = handler.getProperties();
        Assert.assertNotNull(hapticProperties);
        Assert.assertEquals(FunscriptProperties.class, hapticProperties.getClass());
        FunscriptProperties properties = (FunscriptProperties) hapticProperties;
        Assert.assertEquals("1.0", properties.getVersion());
        Assert.assertEquals(90, properties.getRange());
        Assert.assertEquals(false, properties.isInverted());
        List<HapticCommand> hapticCommands = handler.getCommands();
        Assert.assertNotNull(hapticCommands);
        Assert.assertEquals(3, hapticCommands.size());
        for (HapticCommand command : hapticCommands) {
            Assert.assertEquals(FunscriptCommand.class, command.getClass());
        }
        Assert.assertEquals(1000, hapticCommands.get(0).getTime());
        Assert.assertEquals(75, ((FunscriptCommand) hapticCommands.get(0)).getPosition());
        Assert.assertEquals(2500, hapticCommands.get(1).getTime());
        Assert.assertEquals(100, ((FunscriptCommand) hapticCommands.get(1)).getPosition());
        Assert.assertEquals(3100, hapticCommands.get(2).getTime());
        Assert.assertEquals(0, ((FunscriptCommand) hapticCommands.get(2)).getPosition());

        SparseArrayCompat<List<ButtplugDeviceMessage>> buttplugCommands = HapticCommandToButtplugMessage.hapticCommandToButtplugMessage(hapticCommands, hapticProperties);
        Assert.assertEquals(37, buttplugCommands.size());

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

        // Fleshlight Launch commands
        Assert.assertEquals(FleshlightLaunchFW12Cmd.class, buttplugCommands.get(0).get(0).getClass());
        FleshlightLaunchFW12Cmd fleshlightCommand = (FleshlightLaunchFW12Cmd) buttplugCommands.get(0).get(0);
        Assert.assertEquals(14, fleshlightCommand.getSpeed());
        Assert.assertEquals(72, fleshlightCommand.getPosition());
        Assert.assertEquals(FleshlightLaunchFW12Cmd.class, buttplugCommands.get(1000).get(0).getClass());
        fleshlightCommand = (FleshlightLaunchFW12Cmd) buttplugCommands.get(1000).get(0);
        Assert.assertEquals(5, fleshlightCommand.getSpeed());
        Assert.assertEquals(95, fleshlightCommand.getPosition());
        Assert.assertEquals(FleshlightLaunchFW12Cmd.class, buttplugCommands.get(2500).get(0).getClass());
        fleshlightCommand = (FleshlightLaunchFW12Cmd) buttplugCommands.get(2500).get(0);
        Assert.assertEquals(33, fleshlightCommand.getSpeed());
        Assert.assertEquals(5, fleshlightCommand.getPosition());

        // Vibrate commands
        Assert.assertEquals(SingleMotorVibrateCmd.class, buttplugCommands.get(0).get(1).getClass());
        SingleMotorVibrateCmd vibrateCommand = (SingleMotorVibrateCmd) buttplugCommands.get(0).get(1);
        Assert.assertEquals(0.0, vibrateCommand.getSpeed(), 0);
        Assert.assertEquals(SingleMotorVibrateCmd.class, buttplugCommands.get(1000).get(1).getClass());
        vibrateCommand = (SingleMotorVibrateCmd) buttplugCommands.get(1000).get(1);
        Assert.assertEquals(0.75, vibrateCommand.getSpeed(), 0);
        Assert.assertEquals(SingleMotorVibrateCmd.class, buttplugCommands.get(2500).get(1).getClass());
        vibrateCommand = (SingleMotorVibrateCmd) buttplugCommands.get(2500).get(1);
        Assert.assertEquals(1.0, vibrateCommand.getSpeed(), 0);
        Assert.assertEquals(SingleMotorVibrateCmd.class, buttplugCommands.get(3100).get(0).getClass());
        vibrateCommand = (SingleMotorVibrateCmd) buttplugCommands.get(3100).get(0);
        Assert.assertEquals(0.0, vibrateCommand.getSpeed(), 0);

        // Vorze commands
        Assert.assertEquals(VorzeA10CycloneCmd.class, buttplugCommands.get(0).get(2).getClass());
        VorzeA10CycloneCmd vorzeCommand = (VorzeA10CycloneCmd) buttplugCommands.get(0).get(2);
        Assert.assertEquals(14, vorzeCommand.getSpeed());
        Assert.assertEquals(false, vorzeCommand.clockwise);
        Assert.assertEquals(VorzeA10CycloneCmd.class, buttplugCommands.get(972).get(0).getClass());
        vorzeCommand = (VorzeA10CycloneCmd) buttplugCommands.get(972).get(0);
        Assert.assertEquals(0, vorzeCommand.getSpeed());
        Assert.assertEquals(true, vorzeCommand.clockwise);
        Assert.assertEquals(VorzeA10CycloneCmd.class, buttplugCommands.get(1000).get(2).getClass());
        vorzeCommand = (VorzeA10CycloneCmd) buttplugCommands.get(1000).get(2);
        Assert.assertEquals(3, vorzeCommand.getSpeed());
        Assert.assertEquals(false, vorzeCommand.clockwise);
        Assert.assertEquals(VorzeA10CycloneCmd.class, buttplugCommands.get(1972).get(0).getClass());
        vorzeCommand = (VorzeA10CycloneCmd) buttplugCommands.get(1972).get(0);
        Assert.assertEquals(0, vorzeCommand.getSpeed());
        Assert.assertEquals(true, vorzeCommand.clockwise);
        Assert.assertEquals(VorzeA10CycloneCmd.class, buttplugCommands.get(2500).get(2).getClass());
        vorzeCommand = (VorzeA10CycloneCmd) buttplugCommands.get(2500).get(2);
        Assert.assertEquals(33, vorzeCommand.getSpeed());
        Assert.assertEquals(true, vorzeCommand.clockwise);
        Assert.assertEquals(VorzeA10CycloneCmd.class, buttplugCommands.get(3100).get(1).getClass());
        vorzeCommand = (VorzeA10CycloneCmd) buttplugCommands.get(3100).get(1);
        Assert.assertEquals(0, vorzeCommand.getSpeed());
        Assert.assertEquals(true, vorzeCommand.clockwise);
    }
}
