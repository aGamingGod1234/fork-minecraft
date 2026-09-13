package dev.fork.integration;

import com.google.gson.*;
import dev.fork.gameplay.ForkEngine;
import java.util.*;

/** Strict schema validation occurs before the authoritative engine sees any proposal. */
public final class ForkWire {
    private static void keys(JsonObject o,String... expected) { if(!o.keySet().equals(Set.of(expected))) throw new IllegalArgumentException("Malformed FORK keys"); }
    private static String text(JsonObject o,String key) {
        var v=o.get(key); if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected text: "+key);
        String s=v.getAsString(); if(s.isBlank()||s.length()>128) throw new IllegalArgumentException("Unbounded text: "+key); return s;
    }
    private static long number(JsonObject o,String key) {
        var v=o.get(key); if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isNumber()||!v.getAsString().matches("0|[1-9][0-9]*")) throw new IllegalArgumentException("Expected integer: "+key);
        return v.getAsLong();
    }
    public static ForkEngine.Ticket ticket(JsonObject o) {
        keys(o,"branch","epoch","round","baseRevision","requestId"); long round=number(o,"round"); if(round<1||round>6) throw new IllegalArgumentException("Round outside 1..6");
        return new ForkEngine.Ticket(text(o,"branch"),number(o,"epoch"),(int)round,number(o,"baseRevision"),text(o,"requestId"));
    }
    public static ForkEngine.Batch batch(JsonObject o) {
        keys(o,"ticket","intents"); var t=ticket(o.getAsJsonObject("ticket")); var a=o.getAsJsonArray("intents");
        if(a.size()!=3) throw new IllegalArgumentException("Three roles required"); var intents=new ArrayList<ForkEngine.Intent>();
        for(var value:a) { var i=value.getAsJsonObject(); keys(i,"role","actionId","action"); intents.add(new ForkEngine.Intent(ForkEngine.Role.valueOf(text(i,"role")),text(i,"actionId"),text(i,"action"))); }
        return new ForkEngine.Batch(t,intents);
    }
}
