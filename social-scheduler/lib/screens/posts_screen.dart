import 'package:material_ui/material_ui.dart';

import '../app_state.dart';
import '../models.dart';
import '../widgets/common.dart';

/// Every post, split by where it is in its life.
class PostsScreen extends StatelessWidget {
  const PostsScreen({super.key, required this.state, required this.onOpenPost, required this.onCompose});

  final AppState state;
  final void Function(String id) onOpenPost;
  final void Function({DateTime? day}) onCompose;

  @override
  Widget build(BuildContext context) {
    final now = DateTime.now();
    final attention = state.attention(now)..sort((a, b) => (b.when ?? now).compareTo(a.when ?? now));
    final attentionIds = attention.map((p) => p.id).toSet();

    final upcoming = state.posts
        .where((p) => (p.status == 'scheduled' || p.status == 'publishing') && !attentionIds.contains(p.id))
        .toList()
      ..sort((a, b) => (a.scheduledAt ?? now).compareTo(b.scheduledAt ?? now));
    final drafts = state.posts.where((p) => p.isDraft).toList()
      ..sort((a, b) => (b.createdAt ?? now).compareTo(a.createdAt ?? now));
    final published = state.posts.where((p) => p.status == 'published').toList()
      ..sort((a, b) => (b.publishedAt ?? now).compareTo(a.publishedAt ?? now));

    return DefaultTabController(
      length: 4,
      child: Scaffold(
        appBar: AppBar(
          title: const Text('Posts'),
          bottom: TabBar(
            labelPadding: const EdgeInsets.symmetric(horizontal: 4),
            tabs: [
              const Tab(text: 'Upcoming'),
              Tab(
                child: Row(mainAxisSize: MainAxisSize.min, children: [
                  const Flexible(child: Text('To do', softWrap: false, overflow: TextOverflow.fade)),
                  if (attention.isNotEmpty) ...[
                    const SizedBox(width: 6),
                    Badge(label: Text('${attention.length}')),
                  ],
                ]),
              ),
              const Tab(text: 'Drafts'),
              const Tab(text: 'Published'),
            ],
          ),
        ),
        body: TabBarView(
          children: [
            _PostList(
              state: state,
              posts: upcoming,
              onOpenPost: onOpenPost,
              emptyIcon: Icons.event_available_outlined,
              emptyTitle: 'Nothing scheduled',
              emptyMessage: 'Tap New post to plan your first one.',
            ),
            _PostList(
              state: state,
              posts: attention,
              onOpenPost: onOpenPost,
              emptyIcon: Icons.task_alt,
              emptyTitle: 'All clear',
              emptyMessage: 'Posts that fail, or that you chose to post yourself, show up here when they need you.',
            ),
            _PostList(
              state: state,
              posts: drafts,
              onOpenPost: onOpenPost,
              emptyIcon: Icons.edit_note,
              emptyTitle: 'No drafts',
              emptyMessage: 'Save a post as a draft to finish it later.',
            ),
            _PostList(
              state: state,
              posts: published,
              onOpenPost: onOpenPost,
              emptyIcon: Icons.history,
              emptyTitle: 'Nothing published yet',
              emptyMessage: 'Published posts are listed here with links to see them live.',
            ),
          ],
        ),
      ),
    );
  }
}

class _PostList extends StatelessWidget {
  const _PostList({
    required this.state,
    required this.posts,
    required this.onOpenPost,
    required this.emptyIcon,
    required this.emptyTitle,
    required this.emptyMessage,
  });

  final AppState state;
  final List<Post> posts;
  final void Function(String id) onOpenPost;
  final IconData emptyIcon;
  final String emptyTitle;
  final String emptyMessage;

  @override
  Widget build(BuildContext context) {
    return RefreshIndicator(
      onRefresh: state.refreshPosts,
      child: state.loading
          ? const Center(child: CircularProgressIndicator())
          : posts.isEmpty
              ? LayoutBuilder(
                  builder: (context, constraints) => SingleChildScrollView(
                    physics: const AlwaysScrollableScrollPhysics(),
                    child: SizedBox(
                      height: constraints.maxHeight,
                      child: EmptyState(icon: emptyIcon, title: emptyTitle, message: emptyMessage),
                    ),
                  ),
                )
              : ListView.separated(
                  padding: const EdgeInsets.fromLTRB(12, 12, 12, 96),
                  itemCount: posts.length,
                  separatorBuilder: (_, _) => const SizedBox(height: 8),
                  itemBuilder: (context, i) =>
                      PostCard(post: posts[i], state: state, onTap: () => onOpenPost(posts[i].id)),
                ),
    );
  }
}
