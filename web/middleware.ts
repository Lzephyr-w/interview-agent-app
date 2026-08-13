import { NextResponse, type NextRequest } from "next/server";

export function middleware(request: NextRequest) {
  const loggedIn = request.cookies.has("interview_agent_authenticated");
  const isLogin = request.nextUrl.pathname === "/login";

  if (!loggedIn && !isLogin) {
    return NextResponse.redirect(new URL("/login", request.url));
  }
  if (loggedIn && isLogin) {
    return NextResponse.redirect(new URL("/", request.url));
  }
  return NextResponse.next();
}

export const config = {
  matcher: [
    "/",
    "/login",
    "/interviews/:path*",
    "/library/:path*",
    "/mock-interviews/:path*",
    "/ai-mock-interviews/:path*",
    "/weaknesses/:path*",
    "/ai-conversations/:path*",
  ],
};
