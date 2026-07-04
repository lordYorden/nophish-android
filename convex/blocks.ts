import { v } from "convex/values";
import { mutation, query } from "./_generated/server";
import { assertCircleMember } from "./lib/auth";

export const blockFromEvent = mutation({
  args: {
    eventId: v.string(),
  },
  handler: async (ctx, args) => {
    const identity = await ctx.auth.getUserIdentity();
    if (!identity) {
      throw new Error("Unauthenticated");
    }

    const event = await ctx.db
      .query("event")
      .withIndex("byEventId", (q) => q.eq("eventId", args.eventId))
      .first();

    if (!event) {
      throw new Error("Event not found");
    }

    await assertCircleMember(ctx, event.circleId, identity.subject);

    const packageName = event.packageName;
    if (!packageName || packageName.trim() === "") {
      throw new Error("Cannot block event without packageName");
    }

    const existingBlock = await ctx.db
      .query("tempAppBlocks")
      .withIndex("byEventId", (q) => q.eq("eventId", args.eventId))
      .first();

    if (existingBlock) {
      return existingBlock._id;
    }

    const activePackageBlock = await ctx.db
      .query("tempAppBlocks")
      .withIndex("byTargetAndPackage", (q) =>
        q.eq("targetId", event.userId).eq("packageName", packageName),
      )
      .first();

    if (activePackageBlock) {
      throw new Error("Target app is already temporarily blocked");
    }

    const blockId = await ctx.db.insert("tempAppBlocks", {
      eventId: event.eventId,
      circleId: event.circleId,
      targetId: event.userId,
      packageName,
      blockedBy: identity.subject,
      createdAt: Date.now(),
    });

    await ctx.db.patch(event._id, {
      requiresAction: true,
    });

    return blockId;
  },
});

export const releaseForEvent = mutation({
  args: {
    eventId: v.string(),
  },
  handler: async (ctx, args) => {
    const identity = await ctx.auth.getUserIdentity();
    if (!identity) {
      throw new Error("Unauthenticated");
    }

    const event = await ctx.db
      .query("event")
      .withIndex("byEventId", (q) => q.eq("eventId", args.eventId))
      .first();

    if (!event) {
      throw new Error("Event not found");
    }

    await assertCircleMember(ctx, event.circleId, identity.subject);

    const existingBlock = await ctx.db
      .query("tempAppBlocks")
      .withIndex("byEventId", (q) => q.eq("eventId", args.eventId))
      .first();

    if (existingBlock) {
      await ctx.db.delete(existingBlock._id);
    }

    await ctx.db.patch(event._id, {
      requiresAction: false,
    });

    return null;
  },
});

export const getActiveForApp = query({
  args: {
    packageName: v.string(),
  },
  handler: async (ctx, args) => {
    const identity = await ctx.auth.getUserIdentity();
    if (!identity) {
      throw new Error("Unauthenticated");
    }

    if (args.packageName.trim() === "") {
      throw new Error("packageName must not be blank");
    }

    return await ctx.db
      .query("tempAppBlocks")
      .withIndex("byTargetAndPackage", (q) =>
        q.eq("targetId", identity.subject).eq("packageName", args.packageName),
      )
      .first();
  },
});

export const getActiveByEvent = query({
  args: {
    eventId: v.string(),
  },
  handler: async (ctx, args) => {
    const identity = await ctx.auth.getUserIdentity();
    if (!identity) {
      throw new Error("Unauthenticated");
    }

    const block = await ctx.db
      .query("tempAppBlocks")
      .withIndex("byEventId", (q) => q.eq("eventId", args.eventId))
      .first();

    if (!block) {
      return null;
    }

    if (block.targetId === identity.subject) {
      return block;
    }

    await assertCircleMember(ctx, block.circleId, identity.subject);

    return block;
  },
});
