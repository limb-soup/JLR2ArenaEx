package io.github.catizard.jlr2arenaex.network;

import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;
import org.msgpack.value.ArrayValue;
import org.msgpack.value.Value;

import java.io.IOException;
import java.util.Objects;

public class Peer {
    private String userName;
    private String selectedMD5 = "";
    private boolean ready;
    private Score score = new Score();
    private int totalNotes;
    private int option;
    private int gauge;

    public Peer() {

    }

    public Peer(Value value) {
        ArrayValue arr = value.asArrayValue();
        this.userName = arr.get(0).asStringValue().asString();
        this.selectedMD5 = arr.get(1).asStringValue().asString();
        this.ready = arr.get(2).asBooleanValue().getBoolean();
        this.score = new Score(arr.get(3));
        this.option = arr.get(4).asIntegerValue().toInt();
        this.gauge = arr.get(5).asIntegerValue().toInt();
        if (arr.size() >= 6)
            this.totalNotes = arr.get(6).asIntegerValue().toInt();
        else this.totalNotes = 0;
    }

    public byte[] pack() {
        try {
            MessageBufferPacker packer = MessagePack.newDefaultBufferPacker();
            packer.packArrayHeader(6);
            packer.packString(userName);
            packer.packString(selectedMD5);
            packer.packBoolean(ready);
            packer.writePayload(score.pack());
            packer.packInt(option);
            packer.packInt(gauge);
            packer.packInt(totalNotes);
            packer.close();
            return packer.toByteArray();
        } catch (IOException e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    public void reset() {
        this.selectedMD5 = "";
        this.score = new Score();
        this.ready = false;
    }

    public String getUserName() {
        return userName;
    }

    public void setUserName(String userName) {
        this.userName = userName;
    }

    public String getSelectedMD5() {
        return selectedMD5;
    }

    public void setSelectedMD5(String selectedMD5) {
        this.selectedMD5 = selectedMD5;
    }

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }

    public Score getScore() {
        return score;
    }

    public void setScore(Score score) {
        this.score = score;
    }

    public int getTotalNotes(){
        return totalNotes;
    }

    public void setTotalNotes(int totalNotes){
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

    public int getExScore() {
        return this.score.getpGreat() * 2 + this.score.getGreat();
    }

    public int getBP() {
        return this.score.getBad() + this.score.getPoor();
    }

    public int getMaxCombo() {
        return this.score.getMaxCombo();
    }

    public float getRate() {
        return this.getScore().getCurrentNotes() == 0
                ? 0f
                : this.getExScore() * 50f / this.getScore().getCurrentNotes();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) return false;
        Peer peer = (Peer) o;
        return ready == peer.ready && totalNotes == peer.totalNotes && option == peer.option && gauge == peer.gauge && Objects.equals(userName, peer.userName) && Objects.equals(selectedMD5, peer.selectedMD5) && Objects.equals(score, peer.score);
    }

    @Override
    public int hashCode() {
        return Objects.hash(userName, selectedMD5, ready, score, option, gauge, totalNotes);
    }
}
