package smithereen.routes;

import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

import smithereen.Config;
import smithereen.exceptions.ObjectNotFoundException;
import smithereen.Utils;
import smithereen.activitypub.ActivityPubWorker;
import smithereen.data.Account;
import smithereen.data.ForeignGroup;
import smithereen.data.Group;
import smithereen.data.Post;
import smithereen.data.SessionInfo;
import smithereen.data.User;
import smithereen.data.UserInteractions;
import smithereen.data.WebDeltaResponseBuilder;
import smithereen.exceptions.UserActionNotAllowedException;
import smithereen.storage.DatabaseUtils;
import smithereen.storage.GroupStorage;
import smithereen.storage.LikeStorage;
import smithereen.storage.PostStorage;
import smithereen.storage.UserStorage;
import smithereen.templates.RenderedTemplateResponse;
import spark.Request;
import spark.Response;
import spark.Session;
import spark.utils.StringUtils;

import static smithereen.Utils.*;

public class GroupsRoutes{

	private static Group getGroup(Request req) throws SQLException{
		int id=parseIntOrDefault(req.params(":id"), 0);
		Group group=GroupStorage.getByID(id);
		if(group==null){
			throw new ObjectNotFoundException("err_group_not_found");
		}
		return group;
	}

	private static Group getGroupAndRequireLevel(Request req, Account self, Group.AdminLevel level) throws SQLException{
		Group group=getGroup(req);
		if(!GroupStorage.getGroupMemberAdminLevel(group.id, self.user.id).isAtLeast(level)){
			throw new UserActionNotAllowedException();
		}
		return group;
	}

	public static Object myGroups(Request req, Response resp, Account self) throws SQLException{
		jsLangKey(req, "cancel", "create");
		RenderedTemplateResponse model=new RenderedTemplateResponse("groups").with("tab", "groups").with("title", lang(req).get("groups"));
		model.with("groups", GroupStorage.getUserGroups(self.user.id));
		return model.renderToString(req);
	}

	public static Object createGroup(Request req, Response resp, Account self) throws SQLException{
		RenderedTemplateResponse model=new RenderedTemplateResponse("create_group");
		return wrapForm(req, resp, "create_group", "/my/groups/create", lang(req).get("create_group"), "create", model);
	}

	private static Object groupCreateError(Request req, Response resp, String errKey){
		if(isAjax(req)){
			return new WebDeltaResponseBuilder(resp).show("formMessage_createGroup").setContent("formMessage_createGroup", lang(req).get(errKey)).json();
		}
		RenderedTemplateResponse model=new RenderedTemplateResponse("create_group");
		model.with("groupName", req.queryParams("name")).with("groupUsername", req.queryParams("username"));
		return wrapForm(req, resp, "create_group", "/my/groups/create", lang(req).get("create_group"), "create", model);
	}

	public static Object doCreateGroup(Request req, Response resp, Account self) throws SQLException{
		String username=req.queryParams("username");
		String name=req.queryParams("name");

		if(!isValidUsername(username))
			return groupCreateError(req, resp, "err_group_invalid_username");
		if(isReservedUsername(username))
			return groupCreateError(req, resp, "err_group_reserved_username");

		final int[] id={0};
		boolean r=DatabaseUtils.runWithUniqueUsername(username, ()->{
			id[0]=GroupStorage.createGroup(name, username, self.user.id);
		});

		if(r){
			if(isAjax(req)){
				return new WebDeltaResponseBuilder(resp).replaceLocation("/"+username).json();
			}else{
				resp.redirect(Config.localURI("/"+username).toString());
				return "";
			}
		}else{
			return groupCreateError(req, resp, "err_group_username_taken");
		}
	}

	public static Object groupProfile(Request req, Response resp, Group group) throws SQLException{
		int pageOffset=parseIntOrDefault(req.queryParams("offset"), 0);
		SessionInfo info=Utils.sessionInfo(req);
		@Nullable Account self=info!=null ? info.account : null;

		List<User> members=GroupStorage.getRandomMembersForProfile(group.id);
		int[] totalPosts={0};
		List<Post> wall=PostStorage.getWallPosts(group.id, true, 0, 0, pageOffset, totalPosts, false);
		List<Integer> postIDs=wall.stream().map((Post p)->p.id).collect(Collectors.toList());
		HashMap<Integer, UserInteractions> interactions=PostStorage.getPostInteractions(postIDs, self!=null ? self.user.id : 0);

		RenderedTemplateResponse model=new RenderedTemplateResponse("group");
		model.with("group", group).with("members", members).with("postCount", totalPosts[0]).with("pageOffset", pageOffset).with("wall", wall);
		model.with("postInteractions", interactions);
		model.with("title", group.name);
		model.with("admins", GroupStorage.getGroupAdmins(group.id));
		jsLangKey(req, "yes", "no", "cancel");
		if(self!=null){
			Group.AdminLevel level=GroupStorage.getGroupMemberAdminLevel(group.id, self.user.id);
			model.with("membershipState", GroupStorage.getUserMembershipState(group.id, self.user.id));
			model.with("groupAdminLevel", level);
			if(level.isAtLeast(Group.AdminLevel.ADMIN)){
				jsLangKey(req, "update_profile_picture", "save", "profile_pic_select_square_version", "drag_or_choose_file", "choose_file",
						"drop_files_here", "picture_too_wide", "picture_too_narrow", "ok", "error", "error_loading_picture",
						"remove_profile_picture", "confirm_remove_profile_picture", "choose_file_mobile");
			}
		}
		return model.renderToString(req);
	}

	public static Object join(Request req, Response resp, Account self) throws SQLException{
		Group group=getGroup(req);
		Group.MembershipState state=GroupStorage.getUserMembershipState(group.id, self.user.id);
		if(state==Group.MembershipState.MEMBER || state==Group.MembershipState.TENTATIVE_MEMBER){
			return wrapError(req, resp, "err_group_already_member");
		}
		GroupStorage.joinGroup(group, self.user.id, false, !(group instanceof ForeignGroup));
		if(group instanceof ForeignGroup){
			ActivityPubWorker.getInstance().sendFollowActivity(self.user, (ForeignGroup) group);
		}
		if(isAjax(req)){
			return new WebDeltaResponseBuilder(resp).refresh().json();
		}
		resp.redirect(Config.localURI("/"+group.getFullUsername()).toString());
		return "";
	}

	public static Object leave(Request req, Response resp, Account self) throws SQLException{
		Group group=getGroup(req);
		Group.MembershipState state=GroupStorage.getUserMembershipState(group.id, self.user.id);
		if(state!=Group.MembershipState.MEMBER && state!=Group.MembershipState.TENTATIVE_MEMBER){
			return wrapError(req, resp, "err_group_not_member");
		}
		GroupStorage.leaveGroup(group, self.user.id, state==Group.MembershipState.TENTATIVE_MEMBER);
		if(group instanceof ForeignGroup){
			ActivityPubWorker.getInstance().sendUnfollowActivity(self.user, (ForeignGroup) group);
		}
		if(isAjax(req)){
			return new WebDeltaResponseBuilder(resp).refresh().json();
		}
		resp.redirect(Config.localURI("/"+group.getFullUsername()).toString());
		return "";
	}

	public static Object editGeneral(Request req, Response resp, Account self) throws SQLException{
		Group group=getGroupAndRequireLevel(req, self, Group.AdminLevel.ADMIN);
		RenderedTemplateResponse model=new RenderedTemplateResponse("group_edit_general");
		model.with("group", group);
		Session s=req.session();
		if(s.attribute("settings.groupEditMessage")!=null){
			model.with("groupEditMessage", s.attribute("settings.groupEditMessage"));
			s.removeAttribute("settings.groupEditMessage");
		}
		return model.renderToString(req);
	}

	public static Object saveGeneral(Request req, Response resp, Account self) throws SQLException{
		Group group=getGroupAndRequireLevel(req, self, Group.AdminLevel.ADMIN);
		String name=req.queryParams("name"), about=req.queryParams("about");
		String message;
		if(StringUtils.isEmpty(name) || name.length()<1){
			message=lang(req).get("group_name_too_short");
		}else{
			if(StringUtils.isEmpty(about))
				about=null;
			else
				about=preprocessPostHTML(about, null);
			GroupStorage.updateGroupGeneralInfo(group, name, about);
			message=lang(req).get("group_info_updated");
		}
		group=GroupStorage.getByID(group.id);
		ActivityPubWorker.getInstance().sendUpdateGroupActivity(group);
		if(isAjax(req)){
			return new WebDeltaResponseBuilder(resp).show("formMessage_groupEdit").setContent("formMessage_groupEdit", message).json();
		}
		req.session().attribute("settings.profileEditMessage", message);
		resp.redirect("/groups/"+group.id+"/edit");
		return "";
	}

	public static Object members(Request req, Response resp) throws SQLException{
		Group group=getGroup(req);
		int offset=parseIntOrDefault(req.queryParams("offset"), 0);
		List<User> users=GroupStorage.getMembers(group.id, offset, 100);
		RenderedTemplateResponse model=new RenderedTemplateResponse(isAjax(req) ? "user_grid" : "content_wrap").with("users", users);
		model.with("pageOffset", offset).with("total", group.memberCount).with("paginationUrlPrefix", "/groups/"+group.id+"/members?offset=");
//		if(isAjax(req)){
//			if(req.queryParams("fromPagination")==null)
//				return new WebDeltaResponseBuilder(resp).box(lang(req).get("likes_title"), model.renderToString(req), "likesList", 596);
//			else
//				return new WebDeltaResponseBuilder(resp).setContent("likesList", model.renderToString(req));
//		}
		model.with("contentTemplate", "user_grid").with("title", lang(req).get("members"));
		return model.renderToString(req);
	}
}
