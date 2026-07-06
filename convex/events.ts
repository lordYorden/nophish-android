import { v } from "convex/values";
import { mutation, query } from "./_generated/server";
import { paginationOptsValidator } from "convex/server";
import { assertCircleMember } from "./lib/auth";

const LIMIT_CAP = 10

export const register = mutation({
  args: {
    circleId: v.string(),
    timestamp: v.number(),
    action: v.optional(v.string()),
    eventId: v.string(),
    contentHash: v.string(),
    packageName: v.optional(v.string()),
    requiresAction: v.boolean(),
  },
  handler: async (ctx, args) => {
    const identity = await ctx.auth.getUserIdentity();
    if (!identity) {
      throw new Error("Unauthenticated");
    }

    await assertCircleMember(ctx, args.circleId, identity.subject);

    const existingEvent = await ctx.db
      .query("event")
      .withIndex("byEventId", (q) => q.eq("eventId", args.eventId))
      .first();

    if (existingEvent) {
        if(existingEvent.circleId != args.circleId){
            throw new Error("unauthorized eventId")
        }

      return existingEvent._id;
    }

    return await ctx.db.insert("event", {
      userId: identity.subject,
      circleId: args.circleId,
      timestamp: args.timestamp,
      action: args.action ?? "malicious_notification",
      eventId: args.eventId,
      contentHash: args.contentHash,
      packageName: args.packageName,
      requiresAction: args.requiresAction,
    });
  },
});

export const get = query({
  args: {
    startTime: v.optional(v.number()),
    paginationOpts: paginationOptsValidator,
  },
  handler: async (ctx, args) => {
    const identity = await ctx.auth.getUserIdentity();
    if (!identity) {
      throw new Error("Unauthenticated");
    }

    const eventsQuery = ctx.db
      .query("event")
      .withIndex("byUserAndDate", (q) => {
        const userQuery = q.eq("userId", identity.subject);
        return args.startTime !== undefined
          ? userQuery.gte("timestamp", args.startTime)
          : userQuery;
      });

    return await eventsQuery.order("desc").paginate(args.paginationOpts);
  },
});


export const get_by_circle = query({
  args: {
    circleId: v.string(),
    startTime: v.optional(v.number()),
    endTime: v.optional(v.number()),
    requiresAction: v.optional(v.boolean()),
    paginationOpts: paginationOptsValidator,
  },
  handler: async (ctx, args) => {
    const identity = await ctx.auth.getUserIdentity();
    if (!identity) {
      throw new Error("Unauthenticated");
    }

    await assertCircleMember(ctx, args.circleId, identity.subject);

    let eventsQuery = ctx.db
      .query("event")
      .withIndex("byCircleAndDate", (q) => q.eq("circleId", args.circleId));

    if (args.startTime !== undefined) {
      eventsQuery = eventsQuery.filter((q) =>
        q.gte(q.field("timestamp"), args.startTime!),
      );
    }

    if (args.endTime !== undefined) {
      eventsQuery = eventsQuery.filter((q) =>
        q.lte(q.field("timestamp"), args.endTime!),
      );
    }

    if (args.requiresAction !== undefined) {
      eventsQuery = eventsQuery.filter((q) =>
        q.eq(q.field("requiresAction"), args.requiresAction!),
      );
    }

    const page = await eventsQuery.order("desc").paginate(args.paginationOpts);
    const eventsWithBlockState = await Promise.all(
      page.page.map(async (event) => {
        const activeBlock = await ctx.db
          .query("tempAppBlocks")
          .withIndex("byEventId", (q) => q.eq("eventId", event.eventId))
          .first();

        return {
          ...event,
          isBlocked: activeBlock !== null,
        };
      }),
    );

    return {
      ...page,
      page: eventsWithBlockState,
    };
  },
});

export const get_recent_by_circle = query({
  args: {
    circleId: v.string(),
    limit: v.number(),
  },
  handler: async (ctx, args) => {
    if (!Number.isInteger(args.limit) || args.limit <= 0) {
      throw new Error("limit must be a positive integer");
    }

    const limit = Math.min(args.limit, LIMIT_CAP);

    const identity = await ctx.auth.getUserIdentity();
    if (!identity) {
      throw new Error("Unauthenticated");
    }

    await assertCircleMember(ctx, args.circleId, identity.subject);

    return await ctx.db
      .query("event")
      .withIndex("byCircleAndDate", (q) => q.eq("circleId", args.circleId))
      .order("desc")
      .take(limit);
  },
});

export const getByEventId = query({
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
      return null;
    }

    await assertCircleMember(ctx, event.circleId, identity.subject);

    return event;
  },
});
