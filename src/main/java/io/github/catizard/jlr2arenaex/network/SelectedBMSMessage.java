package io.github.catizard.jlr2arenaex.network;

import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.value.ArrayValue;
import org.msgpack.value.Value;

import java.util.Objects;

public class SelectedBMSMessage implements EqualsWithoutRandomPort<SelectedBMSMessage> {
    private int randomSeed;
    private String md5;
    private String title;
    private String artist;
    private int totalNotes;
    private int option;
    private int gauge;
    private boolean itemModeEnabled;

    public SelectedBMSMessage() {

    }

    public SelectedBMSMessage(int randomSeed, String md5, String title, String artist, int option, int gauge, boolean itemModeEnabled) {
        this.randomSeed = randomSeed;
        this.md5 = md5;
        this.title = title;
        this.artist = artist;
        this.totalNotes = 0;
        this.option = option;
        this.gauge = gauge;
        this.itemModeEnabled = itemModeEnabled;
    }

    public SelectedBMSMessage(int randomSeed, String md5, String title, String artist, int totalNotes, int option, int gauge, boolean itemModeEnabled) {
        this.randomSeed = randomSeed;
        this.md5 = md5;
        this.title = title;
        this.artist = artist;
        this.totalNotes = totalNotes;
        this.option = option;
        this.gauge = gauge;
        this.itemModeEnabled = itemModeEnabled;
    }

    public SelectedBMSMessage(Value value) {
        ArrayValue arr = value.asArrayValue();
        this.randomSeed = arr.get(0).asIntegerValue().asInt();
        this.md5 = arr.get(1).asStringValue().asString();
        this.title = arr.get(2).asStringValue().asString();
        this.artist = arr.get(3).asStringValue().asString();
        this.option = arr.get(4).asIntegerValue().asInt();
        this.gauge = arr.get(5).asIntegerValue().toInt();
        this.itemModeEnabled = arr.get(6).asBooleanValue().getBoolean();
        if (arr.size() >= 7)
            this.totalNotes = arr.get(7).asIntegerValue().asInt();
        else this.totalNotes = 0;
    }

    public byte[] pack() {
        try {
            MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
            packer.packArrayHeader(8);
            packer.packInt(this.randomSeed);
            packer.packString(md5);
            packer.packString(title);
            packer.packString(artist);
            packer.packInt(option);
            packer.packInt(gauge);
            packer.packBoolean(itemModeEnabled);
            packer.packInt(totalNotes);
            packer.close();
            return packer.toByteArray();
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public int getRandomSeed() {
        return randomSeed;
    }

    public void setRandomSeed(int randomSeed) {
        this.randomSeed = randomSeed;
    }

    public String getMd5() {
        return md5;
    }

    public void setMd5(String md5) {
        this.md5 = md5;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getArtist() {
        return artist;
    }

    public void setArtist(String artist) {
        this.artist = artist;
    }

    public int getTotalNotes() {
        return totalNotes;
    }

    public void setTotalNotes(int totalNotes) {
        this.totalNotes = totalNotes;
    }

    public int getOption() {
        return option;
    }

    public void setOption(int option) {
        this.option = option;
    }

    public int getGauge() {
        return gauge;
    }

    public void setGauge(int gauge) {
        this.gauge = gauge;
    }

    public boolean isItemModeEnabled() {
        return itemModeEnabled;
    }

    public void setItemModeEnabled(boolean itemModeEnabled) {
        this.itemModeEnabled = itemModeEnabled;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        SelectedBMSMessage that = (SelectedBMSMessage) o;
        return randomSeed == that.randomSeed && totalNotes == that.totalNotes && option == that.option && gauge == that.gauge && itemModeEnabled == that.itemModeEnabled && Objects.equals(md5, that.md5) && Objects.equals(title, that.title) && Objects.equals(artist, that.artist);
    }

    @Override
    public int hashCode() {
        return Objects.hash(randomSeed, md5, title, artist, option, gauge, itemModeEnabled, totalNotes);
    }

    @Override
    public boolean equalsWithoutRandomPort(SelectedBMSMessage obj) {
        return equals(obj);
    }
}
