import { Id } from "../_generated/dataModel";

export async function assertCircleMember(ctx: any, circleId: string, userId: string) {
  const ownedCircle = await ctx.db.get(circleId as Id<"cricle">);

  if (ownedCircle?.ownerId === userId) {
    return;
  }

  const membership = await ctx.db
    .query("otpCodes")
    .withIndex("byCircleId", (q: any) => q.eq("circleId", circleId))
    .filter((q: any) => q.eq(q.field("memberId"), userId))
    .first();

  if (!membership) {
    throw new Error("Not a member of this circle");
  }
}
