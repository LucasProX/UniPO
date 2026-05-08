<template>
  <div class="app-shell relative min-h-screen overflow-x-hidden pb-28 lg:h-screen lg:overflow-hidden">
    <header class="mx-auto flex h-[84px] w-[min(1600px,calc(100vw-24px))] items-center justify-between px-1">
      <button class="flex items-center gap-3 text-left" @click="activeView = 'home'">
        <img src="/favicon.svg" alt="UniPO" class="h-12 w-12 rounded-[16px] shadow-lg" />
        <span>
          <span class="block text-lg font-black leading-5 text-[#19202f]">UniPO</span>
          <span class="text-xs font-semibold uppercase tracking-[0.18em] text-[#718097]">Campus Feed</span>
        </span>
      </button>

      <div v-show="activeView === 'home'" class="board-slider hidden md:grid" :style="{ '--active-index': activeBoardIndex }">
        <span class="board-slider__thumb"></span>
        <button
          v-for="board in boards"
          :key="board.code"
          class="board-slider__item"
          :class="activeBoard === board.code ? 'text-white' : 'text-[#657086] hover:text-[#19202f]'"
          @click="selectBoard(board.code)"
        >
          {{ board.name }}
          <span v-if="board.code === 'recommend'" class="absolute -right-1 -top-2 grid h-5 min-w-5 place-items-center rounded-full bg-[#007aff] px-1 text-[10px] font-black text-white">1</span>
        </button>
      </div>

      <div class="flex items-center gap-2">
        <button class="icon-button" title="搜索">
          <Search :size="19" />
        </button>
        <button class="icon-button relative" title="消息" @click="navigateTo('messages')">
          <Bell :size="19" />
          <span v-if="notificationTotalCount" class="absolute -right-1 -top-1 grid h-5 min-w-5 place-items-center rounded-full bg-[#ff3b30] px-1 text-[10px] font-black text-white">{{ notificationTotalCount }}</span>
        </button>
        <button class="hidden rounded-full bg-[#19202f] px-4 py-2 text-sm font-bold text-white sm:inline-flex" @click="openProfileView">
          {{ isAuthenticated ? currentUser.nickname : '登录' }}
        </button>
        <button class="icon-button" title="菜单" @click="accountDrawerOpen = true">
          <Menu :size="20" />
        </button>
      </div>
    </header>

    <main
      class="relative z-10 mx-auto grid min-h-0 w-[min(1600px,calc(100vw-24px))] gap-5 lg:h-[calc(100vh-100px)] lg:overflow-hidden max-[880px]:grid-cols-1 max-[880px]:overflow-visible"
      :class="activeView === 'home' ? 'grid-cols-[280px_minmax(0,1fr)_280px] max-[1180px]:grid-cols-[240px_minmax(0,1fr)]' : 'grid-cols-1'"
    >
      <aside v-if="activeView === 'home'" class="home-left-rail min-h-0 space-y-4 lg:h-full lg:overflow-hidden max-[880px]:hidden">
        <section v-if="isAuthenticated" class="glass-panel rounded-[28px] p-4">
          <div class="flex items-center gap-3">
            <img :src="currentUser.avatarUrl || fallbackAvatar(currentUser.nickname)" alt="" class="h-14 w-14 rounded-[20px] object-cover" />
            <div class="min-w-0">
              <p class="home-profile-name truncate font-black text-[#19202f]">{{ currentUser.nickname }}</p>
            </div>
          </div>
          <button class="level-nameplate level-nameplate--side" :class="levelBadgeClass(currentUser.level)" @click="showLevelCatalog = true">
            <strong>Lv.{{ currentUser.level }}</strong>
            <span>{{ levelTitleFor(currentUser.level) }}</span>
          </button>
          <div class="mt-4 grid grid-cols-3 gap-2">
            <div class="rounded-[18px] bg-white/62 p-3 text-center">
              <p class="text-lg font-black">{{ userStats.posts }}</p>
              <p class="text-[11px] text-[#718097]">作品</p>
            </div>
            <div class="rounded-[18px] bg-white/62 p-3 text-center">
              <p class="text-lg font-black">{{ userStats.following }}</p>
              <p class="text-[11px] text-[#718097]">关注</p>
            </div>
            <div class="rounded-[18px] bg-white/62 p-3 text-center">
              <p class="text-lg font-black">{{ userStats.followers }}</p>
              <p class="text-[11px] text-[#718097]">粉丝</p>
            </div>
          </div>
        </section>
        <section v-else class="glass-panel rounded-[28px] p-4">
          <div class="flex items-center gap-3">
            <span class="grid h-14 w-14 place-items-center rounded-[20px] bg-white/70 text-[#718097]">
              <UserRound :size="24" />
            </span>
            <div class="min-w-0">
              <p class="truncate text-sm font-black text-[#19202f]">未登录</p>
              <p class="mt-1 text-xs font-semibold leading-5 text-[#718097]">登录后显示你的作品和互动数据</p>
            </div>
          </div>
          <button class="mt-4 w-full rounded-full bg-[#19202f] px-4 py-2.5 text-sm font-black text-white" @click="openLoginDialog()">
            登录
          </button>
        </section>

        <section class="glass-panel rounded-[28px] p-4">
          <div class="flex items-end justify-between gap-3">
            <div>
              <p class="text-sm font-black text-[#19202f]">推荐</p>
              <p class="mt-1 text-xs font-semibold text-[#718097]">近 2 小时点赞评论权重榜</p>
            </div>
            <span class="rounded-full bg-[#19202f] px-2.5 py-1 text-xs font-black text-white">{{ hotRankings.length }}</span>
          </div>

          <div class="mt-3 space-y-1">
            <button
              v-for="(post, index) in hotRankings"
              :key="post.id"
              class="hot-rank-row"
              @click="openPost(post)"
            >
              <span class="grid h-6 w-6 place-items-center rounded-full text-xs font-black" :class="index < 3 ? 'bg-[#fff1df] text-[#ff7a00]' : 'bg-white/70 text-[#718097]'">
                <Flame v-if="index < 3" :size="14" fill="currentColor" />
                <span v-else>{{ index + 1 }}</span>
              </span>
              <span class="hot-rank-title min-w-0 flex-1 truncate text-left" :class="index < 3 ? 'font-black text-[#19202f]' : 'font-bold text-[#405067]'">{{ post.title }}</span>
            </button>
          </div>
        </section>
      </aside>

      <section class="min-h-0 min-w-0 lg:h-full" :class="activeView === 'home' ? '' : 'mx-auto w-[min(1180px,100%)]'">
        <div class="home-feed hide-scrollbar min-h-0 lg:h-full lg:overflow-y-auto lg:pr-1">
          <template v-if="activeView === 'home'">
            <section v-if="showSpotlight" class="spotlight-carousel mb-5">
              <div class="spotlight-carousel__media" :style="{ backgroundImage: `url(${activeSpotlight.imageUrl})` }">
                <div class="absolute inset-0 bg-gradient-to-t from-[#101827] via-[#101827]/36 to-transparent"></div>
                <div class="relative flex h-full flex-col justify-between p-6 text-white">
                  <div class="flex items-center justify-between">
                    <span class="rounded-full bg-white/20 px-3 py-1 text-xs font-black backdrop-blur-xl">管理员位</span>
                    <span class="rounded-full bg-white/20 px-3 py-1 text-xs font-black backdrop-blur-xl">{{ activeSpotlight.badge }}</span>
                  </div>
                  <div>
                    <p class="text-sm font-bold text-white/78">{{ activeSpotlight.kicker }}</p>
                    <h2 class="mt-2 max-w-2xl text-[clamp(34px,5vw,64px)] font-black leading-[0.98] tracking-normal">{{ activeSpotlight.title }}</h2>
                    <p class="mt-3 max-w-xl text-sm leading-6 text-white/82">{{ activeSpotlight.summary }}</p>
                  </div>
                </div>
              </div>
              <div class="spotlight-carousel__titles">
                <button
                  v-for="(item, index) in spotlightItems"
                  :key="item.id"
                  class="spotlight-title"
                  :class="spotlightIndex === index ? 'spotlight-title--active' : ''"
                  @click="spotlightIndex = index"
                >
                  <span>{{ item.title }}</span>
                  <small>{{ item.adminOnly ? '仅管理员可编辑' : '重点事件' }}</small>
                </button>
              </div>
            </section>

            <div class="grid gap-5 xl:grid-cols-2">
              <article
                v-for="post in pagedPosts"
                :key="post.id"
                :data-post-id="post.id"
                class="po-card group"
                :class="selectedPost?.id === post.id ? 'po-card--selected' : ''"
                @click="openPost(post)"
              >
                <div class="po-card__body">
                  <div class="po-card__content">
                    <div class="flex items-start justify-between gap-4">
                      <div class="flex min-w-0 items-center gap-3">
                        <button class="author-tap" @click.stop="openAuthorProfile(post.author)">
                          <img :src="post.author.avatarUrl || fallbackAvatar(post.author.nickname)" alt="" class="h-10 w-10 rounded-[14px] object-cover" />
                        </button>
                        <div class="min-w-0">
                          <p class="truncate text-sm font-black text-[#19202f]">{{ post.author.nickname }}</p>
                          <p class="mt-1 truncate text-xs font-semibold text-[#718097]">
                            Lv.{{ post.author.level }} {{ levelTitleFor(post.author.level) }} · {{ roleLabel(post) }}
                          </p>
                        </div>
                      </div>
                      <span class="mini-chip" :class="post.official ? 'border-[#007aff]/20 bg-[#007aff]/10 text-[#007aff]' : ''">{{ boardName(post.board) }}</span>
                    </div>

                    <div class="po-card__middle">
                      <div class="po-card__copy">
                        <h2 class="po-card__title">{{ compactPostTitle(post.title) }}</h2>
                        <p class="po-card__excerpt">{{ plainPostContent(post.excerpt || post.content) }}</p>
                        <div class="flex flex-wrap gap-1.5">
                          <span v-for="tag in post.tags.slice(0, 3)" :key="tag" class="mini-chip">{{ tag }}</span>
                        </div>
                      </div>
                      <div class="po-card__image-wrap">
                        <img :src="post.coverUrl || fallbackCover(post.id)" alt="" class="po-card__image transition duration-300 group-hover:scale-[1.015]" />
                      </div>
                    </div>

                    <div class="hidden">
                      <span class="text-[#19202f]">热评</span>
                      <span class="ml-2 line-clamp-1 inline align-bottom">{{ featuredCommentFor(post) }}</span>
                    </div>

                    <div class="mt-0 flex flex-wrap items-center justify-between gap-3 border-t border-[#e3e9f1] pt-4">
                      <div class="flex gap-4 text-sm font-bold text-[#718097]">
                        <span class="inline-flex items-center gap-1" :class="post.liked ? 'text-[#ff3b30]' : ''"><Heart :size="16" :fill="post.liked ? 'currentColor' : 'none'" />{{ post.likeCount }}</span>
                        <span class="inline-flex items-center gap-1 text-[#007aff]"><MessageCircle :size="16" fill="currentColor" />{{ post.commentCount }}</span>
                        <span class="inline-flex items-center gap-1" :class="post.favorited ? 'text-[#ffb800]' : ''"><Bookmark :size="16" :fill="post.favorited ? 'currentColor' : 'none'" />{{ post.favoriteCount }}</span>
                      </div>
                      <time class="text-sm font-black text-[#19202f]">{{ formatPublishedAt(post.publishedAt) }}</time>
                    </div>
                  </div>

                </div>
              </article>
            </div>
            <div v-if="feedPageCount > 1" class="feed-pagination">
              <button
                v-if="canGoPrevFeedPage"
                class="feed-pagination__button"
                title="上一页"
                @click="goFeedPage(-1)"
              >
                <ChevronLeft :size="20" />
              </button>
              <span class="feed-pagination__count">{{ feedPage }} / {{ feedPageCount }}</span>
              <button
                v-if="canGoNextFeedPage"
                class="feed-pagination__button"
                title="下一页"
                @click="goFeedPage(1)"
              >
                <ChevronRight :size="20" />
              </button>
            </div>
          </template>

          <template v-else-if="activeView === 'compose'">
            <section class="glass-panel rounded-[34px] p-6">
              <p class="text-sm font-black text-[#718097]">登录后发布校园 PO</p>
              <h1 class="mt-2 text-4xl font-black tracking-normal text-[#19202f]">发一条校园 PO</h1>
              <div class="mt-6 grid gap-4">
                <div
                  class="compose-board-selector"
                  :class="composeErrors.board ? 'compose-board-selector--error' : ''"
                  :style="{ '--compose-board-index': composeBoardIndex }"
                  role="radiogroup"
                  aria-label="选择发布范围"
                >
                  <span class="compose-board-selector__thumb"></span>
                  <button
                    v-for="option in composeBoardOptions"
                    :key="option.value"
                    type="button"
                    class="compose-board-option"
                    :class="draft.board === option.value ? 'compose-board-option--active' : ''"
                    role="radio"
                    :aria-checked="draft.board === option.value"
                    @click="selectDraftBoard(option.value)"
                  >
                    <span class="compose-board-option__icon">
                      <component :is="option.icon" :size="19" />
                    </span>
                    <span class="min-w-0">
                      <strong>{{ option.label }}</strong>
                      <small>{{ option.description }}</small>
                    </span>
                  </button>
                </div>
                <p v-if="composeErrors.board" class="compose-field-error">{{ composeErrors.board }}</p>
                <input v-model="draft.title" class="field" :class="composeErrors.title ? 'field--error' : ''" placeholder="标题，比如：数据结构实验报告别只写代码" @input="composeErrors.title = ''" />
                <p v-if="composeErrors.title" class="compose-field-error">{{ composeErrors.title }}</p>
                <section class="compose-editor" :class="composeErrors.content ? 'compose-editor--error' : ''">
                  <div class="compose-editor__toolbar" aria-label="正文工具栏">
                    <div class="compose-editor__group">
                      <button type="button" class="compose-editor__tool" title="加粗" @click="formatDraftContent('bold')">
                        <Bold :size="17" />
                      </button>
                      <button type="button" class="compose-editor__tool compose-editor__tool--text" title="大字号" @click="applyDraftFont('large')">
                        <Type :size="18" />
                        <span>大</span>
                      </button>
                      <button type="button" class="compose-editor__tool compose-editor__tool--text" title="小字号" @click="applyDraftFont('small')">
                        <Type :size="14" />
                        <span>小</span>
                      </button>
                      <button type="button" class="compose-editor__tool" title="插入清单" @click="formatDraftContent('insertUnorderedList')">
                        <ListPlus :size="17" />
                      </button>
                    </div>

                    <div class="compose-editor__group compose-editor__group--emojis" aria-label="常用表情">
                      <button
                        v-for="emoji in editorEmojis"
                        :key="emoji"
                        type="button"
                        class="compose-editor__emoji"
                        :title="`插入 ${emoji}`"
                        @click="insertDraftText(emoji)"
                      >
                        {{ emoji }}
                      </button>
                    </div>

                    <div class="compose-editor__group compose-editor__group--colors" aria-label="文字颜色">
                      <Palette :size="16" />
                      <button
                        v-for="color in editorColors"
                        :key="color.value"
                        type="button"
                        class="compose-editor__swatch"
                        :style="{ '--swatch-color': color.value }"
                        :title="color.label"
                        @click="applyDraftColor(color.value)"
                      ></button>
                    </div>

                    <label class="compose-editor__tool" title="插入图片" @pointerdown.capture="saveDraftSelection">
                      <ImagePlus :size="17" />
                      <input class="sr-only" type="file" accept="image/*" @click="saveDraftSelection" @change="handleInlinePostImageFile" />
                    </label>
                  </div>
                  <div
                    ref="postContentInput"
                    class="compose-editor__body compose-editor__body--rich"
                    contenteditable="true"
                    role="textbox"
                    aria-multiline="true"
                    data-placeholder="写清楚发生了什么、适合谁、下一步怎么做"
                    @input="syncDraftContentFromEditor"
                    @keyup="saveDraftSelection"
                    @mouseup="saveDraftSelection"
                    @blur="syncDraftContentFromEditor"
                    @focus="saveDraftSelection"
                    @paste="handleDraftPaste"
                  ></div>
                  <div class="compose-editor__footer">
                    <span>{{ draftContentLength }}/2000</span>
                    <span v-if="draftInlineImageCount">已插入 {{ draftInlineImageCount }} 张图</span>
                  </div>
                </section>
                <p v-if="composeErrors.content" class="compose-field-error">{{ composeErrors.content }}</p>
                <section class="compose-cover-card">
                  <div v-if="draft.coverUrl" class="compose-cover-card__preview">
                    <img :src="draft.coverUrl" alt="" />
                    <span>封面预览</span>
                  </div>
                  <div class="compose-cover-card__actions">
                    <div>
                      <p>封面图片</p>
                      <span>{{ draft.coverUrl ? '已上传，可以随时更换' : '用于首页卡片和通知缩略图' }}</span>
                    </div>
                    <label class="compose-cover-card__button">
                      <Upload :size="17" />
                      {{ draft.coverUrl ? '更换封面' : '上传封面图片' }}
                      <input class="sr-only" type="file" accept="image/*" @change="handlePostImageFile" />
                    </label>
                  </div>
                </section>
                <button class="rounded-full bg-[#19202f] px-5 py-3 text-sm font-black text-white" @click="publishLocalPost">发布 PO</button>
              </div>
            </section>
          </template>

          <template v-else-if="activeView === 'schedule'">
            <section class="glass-panel rounded-[34px] p-6">
              <div class="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <p class="text-sm font-black text-[#718097]">课程表</p>
                  <h1 class="mt-2 text-4xl font-black tracking-normal text-[#19202f]">第 {{ selectedWeek }} 周课程</h1>
                </div>
                <div class="flex flex-wrap items-center gap-2">
                  <div class="week-switcher">
                    <button @click="selectedWeek = Math.max(1, selectedWeek - 1)"><ChevronLeft :size="16" /></button>
                    <span>第 {{ selectedWeek }} 周</span>
                    <button @click="selectedWeek += 1"><ChevronRight :size="16" /></button>
                  </div>
                  <button class="inline-flex items-center gap-2 rounded-full bg-[#19202f] px-5 py-3 text-sm font-black text-white" @click="mockImportSchedule">
                    <Upload :size="17" />
                    导入课程表
                  </button>
                </div>
              </div>
              <p v-if="importNotice" class="mt-4 rounded-[18px] bg-[#eaf4ff] px-4 py-3 text-sm font-bold text-[#007aff]">已识别 18 节通用课程。后续院花发布统一课表后，同学院同专业会自动同步。</p>

              <div class="schedule-board mt-6 thin-scrollbar">
                <div class="schedule-day" v-for="day in scheduleDays" :key="day.key">
                  <div class="schedule-day__head">
                    <span>{{ day.name }}</span>
                    <small>{{ day.date }}</small>
                  </div>
                  <button
                    v-for="course in coursesForDay(day.key)"
                    :key="course.id"
                    class="course-card"
                    :style="{ '--course-color': course.color }"
                    @click="editCourse(course)"
                  >
                    <span class="flex items-center justify-between gap-2">
                      <strong>{{ course.title }}</strong>
                      <Edit3 :size="14" />
                    </span>
                    <span>{{ course.start }} - {{ course.end }}</span>
                    <span>{{ course.location }} · {{ course.teacher }}</span>
                    <div class="course-card__chips">
                      <em>{{ course.scope }}</em>
                      <em>{{ weekRangeLabel(course) }}</em>
                    </div>
                  </button>
                </div>
              </div>
            </section>
          </template>

          <template v-else-if="activeView === 'messages'">
            <section v-if="activeConversation" class="glass-panel message-thread-panel rounded-[34px]">
              <div class="message-thread-head">
                <button class="icon-button" title="返回" @click="closeConversation">
                  <ChevronLeft :size="20" />
                </button>
                <img :src="activeConversation.peer.avatarUrl || fallbackAvatar(activeConversation.peer.nickname)" alt="" class="h-[52px] w-[52px] rounded-[18px] object-cover" />
                <div class="min-w-0">
                  <div class="message-thread-titleline">
                    <h1 class="truncate text-2xl font-black tracking-normal text-[#19202f]">{{ activeConversation.peer.nickname }}</h1>
                    <button class="level-nameplate level-nameplate--chat" :class="levelBadgeClass(activeConversation.peer.level)" @click="showLevelCatalog = true">
                      <strong>Lv.{{ activeConversation.peer.level }}</strong>
                      <span>{{ levelTitleFor(activeConversation.peer.level) }}</span>
                    </button>
                  </div>
                  <span class="presence-pill mt-1" :class="presenceClass(activeConversation.peer)">
                    <span></span>
                    {{ presenceLabel(activeConversation.peer) }}
                  </span>
                </div>
              </div>

              <div class="message-thread-body thin-scrollbar">
                <div v-if="!activeConversationMessages.length" class="message-thread-empty">
                  <Send :size="28" />
                  <span>还没有私信，先发一句吧</span>
                </div>
                <article
                  v-for="message in activeConversationMessages"
                  :key="message.id"
                  class="message-bubble-row"
                  :class="messageIsMine(message) ? 'message-bubble-row--mine' : ''"
                >
                  <img :src="messageAvatar(message)" alt="" class="message-bubble-avatar" />
                  <div class="message-bubble-stack">
                    <div class="message-bubble" :class="messageIsMine(message) ? 'message-bubble--mine' : ''">
                      {{ message.content }}
                    </div>
                    <div class="message-bubble-meta">
                      <time>{{ formatPublishedAt(message.createdAt) }}</time>
                      <span v-if="messageIsMine(message) && isLastOutgoingMessage(message)" class="message-read-state">{{ message.read ? '已读' : '未读' }}</span>
                    </div>
                  </div>
                </article>
              </div>

              <form class="message-thread-composer" @submit.prevent="sendConversationMessage">
                <textarea
                  v-model="messageInput"
                  maxlength="300"
                  placeholder="发一条私信"
                  @keydown.enter.exact.prevent="sendConversationMessage"
                ></textarea>
                <div class="flex items-center justify-between gap-3">
                  <span class="text-xs font-bold text-[#718097]">{{ messageInput.length }}/300</span>
                  <button class="reply-submit" :disabled="!messageInput.trim()">
                    <Send :size="15" />
                    发送
                  </button>
                </div>
              </form>
            </section>

            <section v-else class="glass-panel rounded-[34px] p-6">
              <p class="text-sm font-black text-[#718097]">消息和提醒</p>
              <h1 class="mt-2 text-4xl font-black tracking-normal text-[#19202f]">互动提醒</h1>

              <div class="message-tabs mt-6" :style="{ '--active-message-index': activeMessageTabIndex }">
                <span class="message-tabs__thumb"></span>
                <button
                  v-for="tab in messageTabs"
                  :key="tab.key"
                  class="message-tabs__item"
                  :class="activeMessageTab === tab.key ? 'text-white' : 'text-[#657086]'"
                  @click="selectMessageTab(tab.key)"
                >
                  <component :is="tab.icon" :size="17" />
                  <span>{{ tab.label }}</span>
                  <span v-if="messageTabUnreadCounts[tab.key]" class="message-tab-badge">{{ messageTabUnreadCounts[tab.key] }}</span>
                </button>
              </div>

              <div class="mt-5 grid gap-3">
                <article v-for="notice in activeNotices" :key="notice.id" class="notice-card">
                  <button class="notice-card__main" type="button" @click="notice.post ? openPost(notice.post) : undefined">
                    <img :src="notice.actor.avatarUrl || fallbackAvatar(notice.actor.nickname)" alt="" class="notice-card__avatar" />
                    <div class="notice-card__copy">
                      <p v-if="notice.commentContent" class="notice-card__message notice-card__message--comment">
                        <span class="notice-card__message-prefix">{{ notice.commentPrefix || `${notice.actor.nickname} 给你评论：` }}</span>
                        <span class="notice-card__message-content">{{ notice.commentContent.trim() }}</span>
                      </p>
                      <p v-else class="notice-card__message">{{ notice.text }}</p>
                    </div>
                  </button>
                  <button v-if="notice.post" class="notice-card__post" type="button" @click="openPost(notice.post)">
                    <img :src="notice.coverUrl || fallbackCover(notice.post.id)" alt="" />
                    <span>{{ notice.post.title }}</span>
                  </button>
                  <div v-else class="notice-card__time-only">
                    <time>{{ formatPublishedAt(notice.createdAt) }}</time>
                  </div>
                  <time v-if="notice.post" class="notice-card__time">{{ formatPublishedAt(notice.createdAt) }}</time>
                </article>
                <div v-if="!activeNotices.length" class="rounded-[24px] bg-white/58 p-5 text-center text-sm font-bold text-[#718097]">
                  这里还没有新的互动
                </div>
              </div>

              <div class="mt-8">
                <div class="flex items-center justify-between">
                  <h2 class="text-xl font-black text-[#19202f]">私信</h2>
                  <span class="text-xs font-black text-[#718097]">{{ privateConversations.length }} 条</span>
                </div>
                <div class="mt-3 grid gap-3">
                  <button
                    v-for="conversation in privateConversations"
                    :key="conversation.id"
                    class="conversation-card"
                    @click="openConversation(conversation)"
                  >
                    <img :src="conversation.peer.avatarUrl || fallbackAvatar(conversation.peer.nickname)" alt="" class="h-12 w-12 rounded-[18px]" />
                    <div class="min-w-0 flex-1">
                      <div class="flex min-w-0 items-center gap-2">
                        <p class="truncate font-black">{{ conversation.peer.nickname }}</p>
                        <span class="presence-pill presence-pill--compact" :class="presenceClass(conversation.peer)">
                          <span></span>
                          {{ presenceLabel(conversation.peer) }}
                        </span>
                      </div>
                      <p class="mt-1 truncate text-sm text-[#718097]">{{ conversation.lastMessage || '打开私信给对方发消息' }}</p>
                    </div>
                    <span v-if="conversation.unreadCount" class="rounded-full bg-[#ff3b30] px-2 py-1 text-xs font-black text-white">{{ conversation.unreadCount }}</span>
                  </button>
                  <div v-if="!privateConversations.length" class="rounded-[24px] bg-white/58 p-5 text-center text-sm font-bold text-[#718097]">
                    还没有私信会话
                  </div>
                </div>
              </div>
            </section>
          </template>

          <template v-else-if="activeView === 'user-profile'">
            <section v-if="viewedProfile" class="glass-panel overflow-hidden rounded-[34px]">
              <div class="h-56 bg-cover bg-center" :style="{ backgroundImage: `url(${profileBanner})` }"></div>
              <div class="px-6 pb-6 pt-5">
                <div class="-mt-10 flex flex-wrap items-end justify-between gap-4">
                  <div class="profile-head__main">
                    <img :src="viewedProfile.profile.avatarUrl || fallbackAvatar(viewedProfile.profile.nickname)" alt="" class="profile-avatar" />
                    <div class="profile-head__text">
                      <div class="flex flex-wrap items-center gap-2">
                        <h1 class="text-3xl font-black tracking-normal">{{ viewedProfile.profile.nickname }}</h1>
                        <button class="level-nameplate level-nameplate--profile" :class="levelBadgeClass(viewedProfile.profile.level)" @click="showLevelCatalog = true">
                          <strong>Lv.{{ viewedProfile.profile.level }}</strong>
                          <span>{{ levelTitleFor(viewedProfile.profile.level) }}</span>
                        </button>
                      </div>
                      <p class="mt-1 text-sm font-bold text-[#718097]">UID {{ viewedProfile.profile.publicUid }} · {{ viewedProfile.profile.college || '试点大学' }} {{ viewedProfile.profile.grade || '' }}</p>
                    </div>
                  </div>
                  <div class="flex flex-wrap items-center gap-2">
                    <button
                      v-if="!viewedProfile.mine"
                      class="rounded-full px-5 py-3 text-sm font-black transition"
                      :class="viewedProfile.following ? 'bg-white text-[#19202f]' : 'bg-[#19202f] text-white'"
                      @click="toggleViewedProfileFollow"
                    >
                      {{ viewedProfile.following ? '已关注' : '关注' }}
                    </button>
                    <button
                      v-if="!viewedProfile.mine && viewedProfile.following"
                      class="inline-flex items-center gap-2 rounded-full bg-[#007aff] px-5 py-3 text-sm font-black text-white transition hover:-translate-y-0.5"
                      @click="openMessageDraft(viewedProfile.profile)"
                    >
                      <Send :size="16" />
                      私信
                    </button>
                  </div>
                </div>

                <div class="profile-social-strip profile-social-strip--plain">
                  <div class="profile-social-pill">
                    <strong>{{ viewedProfile.stats.following }}</strong>
                    <span>关注</span>
                  </div>
                  <div class="profile-social-pill">
                    <strong>{{ viewedProfile.stats.followers }}</strong>
                    <span>粉丝</span>
                  </div>
                </div>

                <div class="profile-copy">
                  <p class="profile-motto">座右铭：{{ extractProfileMotto(viewedProfile.profile.bio) }}</p>
                  <p class="profile-bio">{{ stripProfileMotto(viewedProfile.profile.bio) || '还没有个人简介。' }}</p>
                </div>

                <div class="profile-tabs mt-8" :style="{ '--active-profile-index': activeViewedProfileTabIndex }">
                  <span class="profile-tabs__thumb"></span>
                  <button v-for="tab in viewedProfileTabs" :key="tab.key" class="profile-tabs__item" :class="viewedProfileTab === tab.key ? 'text-white' : 'text-[#657086]'" @click="viewedProfileTab = tab.key">
                    <span>{{ tab.label }}</span>
                    <strong>{{ tab.count }}</strong>
                  </button>
                </div>

                <div class="mt-8 flex items-center justify-between gap-3">
                  <h2 class="text-xl font-black text-[#19202f]">{{ viewedProfileSectionTitle }}（{{ viewedProfileSectionCount }}条）</h2>
                </div>
                <div class="mt-5 grid grid-cols-3 gap-4 max-[1180px]:grid-cols-2 max-[760px]:grid-cols-1">
                  <article v-for="post in viewedProfileGridPosts" :key="post.id" class="po-card group" :class="selectedPost?.id === post.id ? 'po-card--selected' : ''" @click="openPost(post)">
                    <div class="po-card__body">
                      <div class="po-card__content">
                        <div class="flex items-start justify-between gap-4">
                          <div class="flex min-w-0 items-center gap-3">
                            <img :src="post.author.avatarUrl || fallbackAvatar(post.author.nickname)" alt="" class="h-10 w-10 rounded-[14px] object-cover" />
                            <div class="min-w-0">
                              <p class="truncate text-sm font-black text-[#19202f]">{{ post.author.nickname }}</p>
                              <p class="mt-1 truncate text-xs font-semibold text-[#718097]">Lv.{{ post.author.level }} {{ levelTitleFor(post.author.level) }} · {{ roleLabel(post) }}</p>
                            </div>
                          </div>
                          <span class="mini-chip" :class="post.official ? 'border-[#007aff]/20 bg-[#007aff]/10 text-[#007aff]' : ''">{{ boardName(post.board) }}</span>
                        </div>

                        <div class="po-card__middle">
                          <div class="po-card__copy">
                            <h2 class="po-card__title">{{ compactPostTitle(post.title) }}</h2>
                            <p class="po-card__excerpt">{{ plainPostContent(post.excerpt || post.content) }}</p>
                            <div class="flex flex-wrap gap-1.5">
                              <span v-for="tag in post.tags.slice(0, 3)" :key="tag" class="mini-chip">{{ tag }}</span>
                            </div>
                          </div>
                          <div class="po-card__image-wrap">
                            <img :src="post.coverUrl || fallbackCover(post.id)" alt="" class="po-card__image transition duration-300 group-hover:scale-[1.015]" />
                          </div>
                        </div>

                        <div class="mt-0 flex flex-wrap items-center justify-between gap-3 border-t border-[#e3e9f1] pt-4">
                          <div class="flex gap-4 text-sm font-bold text-[#718097]">
                            <span class="inline-flex items-center gap-1" :class="post.liked ? 'text-[#ff3b30]' : ''"><Heart :size="16" :fill="post.liked ? 'currentColor' : 'none'" />{{ post.likeCount }}</span>
                            <span class="inline-flex items-center gap-1 text-[#007aff]"><MessageCircle :size="16" fill="currentColor" />{{ post.commentCount }}</span>
                            <span class="inline-flex items-center gap-1" :class="post.favorited ? 'text-[#ffb800]' : ''"><Bookmark :size="16" :fill="post.favorited ? 'currentColor' : 'none'" />{{ post.favoriteCount }}</span>
                          </div>
                          <time class="text-sm font-black text-[#19202f]">{{ formatPublishedAt(post.publishedAt) }}</time>
                        </div>
                      </div>
                    </div>
                  </article>
                </div>
              </div>
            </section>
          </template>

          <template v-else>
            <section class="glass-panel overflow-hidden rounded-[34px]">
              <div class="h-56 bg-cover bg-center" :style="{ backgroundImage: `url(${profileBanner})` }"></div>
              <div class="px-6 pb-6 pt-5">
                <div class="-mt-10 flex flex-wrap items-end justify-between gap-4">
                  <div class="profile-head__main">
                    <img :src="currentUser.avatarUrl || fallbackAvatar(currentUser.nickname)" alt="" class="profile-avatar" @click="openProfileEditor" />
                    <div class="profile-head__text">
                      <div class="flex flex-wrap items-center gap-2">
                        <h1 class="text-3xl font-black tracking-normal">{{ currentUser.nickname }}</h1>
                        <button class="level-nameplate level-nameplate--profile" :class="levelBadgeClass(currentUser.level)" @click="showLevelCatalog = true">
                          <strong>Lv.{{ currentUser.level }}</strong>
                          <span>{{ levelTitleFor(currentUser.level) }}</span>
                        </button>
                      </div>
                      <p class="mt-1 text-sm font-bold text-[#718097]">UID {{ currentUser.publicUid }}</p>
                    </div>
                  </div>
                  <button class="rounded-full bg-[#19202f] px-5 py-3 text-sm font-black text-white" @click="openProfileEditor">编辑资料</button>
                </div>
                <div class="profile-social-strip">
                  <div class="profile-social-pill">
                    <strong>{{ userStats.following }}</strong>
                    <span>关注</span>
                  </div>
                  <div class="profile-social-pill">
                    <strong>{{ userStats.followers }}</strong>
                    <span>粉丝</span>
                  </div>
                </div>

                <div class="profile-copy">
                  <p class="profile-motto">座右铭：{{ profileMotto }}</p>
                  <p class="profile-bio">{{ stripProfileMotto(currentUser.bio) || '还没有个人简介。' }}</p>
                </div>

                <div class="profile-tabs mt-8" :style="{ '--active-profile-index': activeProfileTabIndex }">
                  <span class="profile-tabs__thumb"></span>
                  <button v-for="tab in profileTabs" :key="tab.key" class="profile-tabs__item" :class="profileTab === tab.key ? 'text-white' : 'text-[#657086]'" @click="profileTab = tab.key">
                    <span>{{ tab.label }}</span>
                    <strong>{{ tab.count }}</strong>
                  </button>
                </div>

                <div class="mt-5 grid grid-cols-3 gap-4 max-[1180px]:grid-cols-2 max-[760px]:grid-cols-1">
                  <article v-for="post in profileGridPosts" :key="post.id" class="po-card group" :class="selectedPost?.id === post.id ? 'po-card--selected' : ''" @click="openPost(post)">
                    <div class="po-card__body">
                      <div class="po-card__content">
                        <div class="flex items-start justify-between gap-4">
                          <div class="flex min-w-0 items-center gap-3">
                            <button class="author-tap" @click.stop="openAuthorProfile(post.author)">
                              <img :src="post.author.avatarUrl || fallbackAvatar(post.author.nickname)" alt="" class="h-10 w-10 rounded-[14px] object-cover" />
                            </button>
                            <div class="min-w-0">
                              <p class="truncate text-sm font-black text-[#19202f]">{{ post.author.nickname }}</p>
                              <p class="mt-1 truncate text-xs font-semibold text-[#718097]">Lv.{{ post.author.level }} {{ levelTitleFor(post.author.level) }} · {{ roleLabel(post) }}</p>
                            </div>
                          </div>
                          <div class="flex shrink-0 items-center gap-2">
                            <button
                              v-if="canDeletePost(post)"
                              class="po-card-delete"
                              :disabled="isDeletingPost(post.id)"
                              title="删除这条 PO"
                              @click.stop="deleteMyPost(post)"
                            >
                              <Trash2 :size="15" />
                              <span>{{ isDeletingPost(post.id) ? '删除中' : '删除' }}</span>
                            </button>
                            <span class="mini-chip" :class="post.official ? 'border-[#007aff]/20 bg-[#007aff]/10 text-[#007aff]' : ''">{{ boardName(post.board) }}</span>
                          </div>
                        </div>

                        <div class="po-card__middle">
                          <div class="po-card__copy">
                            <h2 class="po-card__title">{{ compactPostTitle(post.title) }}</h2>
                            <p class="po-card__excerpt">{{ plainPostContent(post.excerpt || post.content) }}</p>
                            <div class="flex flex-wrap gap-1.5">
                              <span v-for="tag in post.tags.slice(0, 3)" :key="tag" class="mini-chip">{{ tag }}</span>
                            </div>
                          </div>
                          <div class="po-card__image-wrap">
                            <img :src="post.coverUrl || fallbackCover(post.id)" alt="" class="po-card__image transition duration-300 group-hover:scale-[1.015]" />
                          </div>
                        </div>

                        <div class="mt-0 flex flex-wrap items-center justify-between gap-3 border-t border-[#e3e9f1] pt-4">
                          <div class="flex gap-4 text-sm font-bold text-[#718097]">
                            <span class="inline-flex items-center gap-1" :class="post.liked ? 'text-[#ff3b30]' : ''"><Heart :size="16" :fill="post.liked ? 'currentColor' : 'none'" />{{ post.likeCount }}</span>
                            <span class="inline-flex items-center gap-1 text-[#007aff]"><MessageCircle :size="16" fill="currentColor" />{{ post.commentCount }}</span>
                            <span class="inline-flex items-center gap-1" :class="post.favorited ? 'text-[#ffb800]' : ''"><Bookmark :size="16" :fill="post.favorited ? 'currentColor' : 'none'" />{{ post.favoriteCount }}</span>
                          </div>
                          <time class="text-sm font-black text-[#19202f]">{{ formatPublishedAt(post.publishedAt) }}</time>
                        </div>
                      </div>
                    </div>
                  </article>
                </div>
              </div>
            </section>
          </template>
        </div>
      </section>

      <aside v-if="activeView === 'home'" class="home-side-rail min-h-0 space-y-4 lg:h-full lg:overflow-hidden max-[1180px]:col-span-2 max-[1180px]:grid max-[1180px]:h-auto max-[1180px]:grid-cols-2 max-[880px]:col-span-1 max-[880px]:grid-cols-1">
        <section class="glass-panel rounded-[30px] p-4">
          <div class="flex items-end justify-between gap-3">
            <div>
              <p class="text-sm font-black text-[#718097]">今日校园 PO · {{ boardName(activeBoard) }}</p>
              <h2 class="mt-1 text-xl font-black text-[#19202f]">事件日历</h2>
            </div>
            <CalendarDays class="text-[#007aff]" :size="22" />
          </div>

          <div class="calendar-grid mt-4">
            <div v-for="label in calendarWeekLabels" :key="label" class="calendar-week-label">{{ label }}</div>
            <button
              v-for="day in calendarDays"
              :key="day.dateKey"
                  class="calendar-day-button"
                  :class="{
                    'calendar-day-button--muted': !day.inMonth,
                    'calendar-day-button--today': day.dateKey === todayKey,
                    'calendar-day-button--active': selectedDate === day.dateKey,
                    'calendar-day-button--has-posts': day.postCount > 0
                  }"
              @click="selectCalendarDate(day.dateKey)"
            >
              <span>{{ day.day }}</span>
              <small v-if="day.postCount">{{ day.postCount }}</small>
            </button>
          </div>

          <div class="operator-announcements mt-4">
            <div class="flex items-center justify-between gap-3">
              <p>运营公告</p>
              <span>{{ operatorAnnouncements.length }} 条</span>
            </div>
            <div class="operator-announcements__viewport mt-2">
              <div
                class="operator-announcements__track"
                :class="announcementCarouselTransition ? '' : 'operator-announcements__track--instant'"
                :style="{ transform: `translateY(-${announcementCarouselIndex * announcementCarouselStep}px)` }"
              >
                <article v-for="notice in operatorAnnouncementLoop" :key="notice.loopKey" class="operator-announcement-row">
                  <span class="mini-chip">{{ notice.badge }}</span>
                  <div class="min-w-0">
                    <strong>{{ notice.title }}</strong>
                    <small>{{ notice.summary }}</small>
                  </div>
                </article>
              </div>
            </div>
          </div>
        </section>

        <section class="glass-panel rounded-[30px] p-5 checkin-card" :class="checkinCelebrating ? 'checkin-card--celebrating' : ''">
          <div class="flex items-start justify-between gap-3">
            <div>
              <p class="text-sm font-black text-[#718097]">签到</p>
              <h2 class="mt-1 text-2xl font-black text-[#19202f]">{{ isAuthenticated ? `Lv.${currentUser.level} ${levelTitleFor(currentUser.level)}` : '登录后查看等级' }}</h2>
            </div>
            <span class="relative grid h-12 w-12 place-items-center rounded-[18px] bg-[#19202f] text-white">
              <CheckCircle2 :size="23" />
              <span v-if="checkinCelebrating" class="xp-pop">+{{ lastCheckinXp }}</span>
            </span>
          </div>

          <div class="mt-4">
            <div class="flex items-center justify-between text-xs font-black text-[#718097]">
              <span>{{ isAuthenticated ? `${currentUser.xp} XP` : '登录后同步' }}</span>
              <span>{{ isAuthenticated ? `${currentLevelNeed} XP` : '-- XP' }}</span>
            </div>
            <div class="mt-2 h-3 overflow-hidden rounded-full bg-white/72">
              <span class="block h-full rounded-full bg-[#007aff]" :style="{ width: `${isAuthenticated ? levelProgress : 0}%` }"></span>
            </div>
          </div>

          <blockquote class="daily-quote mt-4">
            <p>{{ dailyQuote.text }}</p>
            <cite>{{ dailyQuote.source }}</cite>
          </blockquote>

          <p class="mt-4 text-xs leading-5 text-[#718097]">签到是稳定入口，连签会加成；日活经验上限 900 XP，重度用户约 1 年满级。</p>
          <button
            class="mt-4 w-full rounded-full px-5 py-3 text-sm font-black transition"
            :class="checkedInToday ? 'bg-white text-[#718097]' : 'bg-[#19202f] text-white hover:-translate-y-0.5'"
            :disabled="checkingIn"
            @click="doCheckIn"
          >
            {{ checkinButtonText }}
          </button>
        </section>
      </aside>
    </main>

    <nav class="fixed bottom-5 left-1/2 z-40 h-[92px] -translate-x-1/2 rounded-[32px] border border-white/70 bg-white/48 px-3 shadow-[0_24px_90px_rgba(25,38,59,0.24)] backdrop-blur-2xl">
      <div class="relative flex h-full items-center">
        <span class="absolute left-0 top-2 h-[78px] w-[92px] rounded-[26px] bg-white/90 shadow-[0_10px_32px_rgba(25,38,59,0.16)] transition-transform duration-300 ease-[cubic-bezier(.22,1,.36,1)]" :style="{ transform: `translateX(${activeDockIndex * 92}px)` }"></span>
        <button
          v-for="item in dockItems"
          :key="item.key"
          class="relative z-10 flex h-[84px] w-[92px] flex-col items-center justify-center gap-1 text-[10px] font-black transition"
          :class="activeView === item.key ? 'text-[#19202f]' : 'text-[#718097]'"
          @click="navigateTo(item.key)"
        >
          <component :is="item.icon" :size="26" />
          <span>{{ item.label }}</span>
        </button>
      </div>
    </nav>

    <Transition name="detail-pop">
      <div v-if="expandedPost" class="fixed inset-0 z-50 grid place-items-center bg-[#0f172a]/38 p-4 backdrop-blur-sm" @click.self="expandedPost = null">
        <article class="detail-panel thin-scrollbar">
          <button class="absolute right-5 top-5 z-10 grid h-10 w-10 place-items-center rounded-full bg-white/90 text-[#19202f]" @click="expandedPost = null">
            <X :size="19" />
          </button>
          <div class="p-5 sm:p-7">
            <div class="flex items-start justify-between gap-4 pr-12">
              <div class="flex min-w-0 items-center gap-3">
                <button class="author-tap" @click="openAuthorProfile(expandedPost.author)">
                  <img :src="expandedPost.author.avatarUrl || fallbackAvatar(expandedPost.author.nickname)" alt="" class="h-14 w-14 rounded-[18px] object-cover" />
                </button>
                <div class="min-w-0">
                  <p class="truncate text-base font-black text-[#19202f]">{{ expandedPost.author.nickname }}</p>
                  <p class="mt-1 truncate text-xs font-semibold text-[#718097]">Lv.{{ expandedPost.author.level }} {{ levelTitleFor(expandedPost.author.level) }} · {{ roleLabel(expandedPost) }}</p>
                </div>
              </div>
              <span class="mini-chip">{{ boardName(expandedPost.board) }}</span>
            </div>

            <h1 class="mt-6 text-[clamp(30px,5vw,48px)] font-black leading-tight tracking-normal text-[#19202f]">{{ expandedPost.title }}</h1>
            <div class="rich-post-content mt-4" v-html="renderRichPostContent(expandedPost.content)"></div>

            <div class="mt-5 flex flex-wrap gap-2">
              <span v-for="tag in expandedPost.tags" :key="tag" class="mini-chip">{{ tag }}</span>
            </div>
            <time class="mt-3 block text-right text-sm font-black text-[#19202f]">{{ formatPublishedAt(expandedPost.publishedAt) }}</time>

            <div class="mt-6 grid grid-cols-3 gap-3">
              <button class="action-metric action-metric--like" :class="expandedPost.liked ? 'action-metric--active' : ''" @click="togglePostLike(expandedPost)">
                <Heart :size="28" :fill="expandedPost.liked ? 'currentColor' : 'none'" />
                <strong>{{ expandedPost.likeCount }}</strong>
                <span>点赞</span>
              </button>
              <button class="action-metric action-metric--comment" @click="togglePostCommentBox">
                <MessageCircle :size="28" fill="currentColor" />
                <strong>{{ expandedPost.commentCount }}</strong>
                <span>评论</span>
              </button>
              <button class="action-metric action-metric--favorite" :class="expandedPost.favorited ? 'action-metric--active' : ''" @click="togglePostFavorite(expandedPost)">
                <Bookmark :size="28" :fill="expandedPost.favorited ? 'currentColor' : 'none'" />
                <strong>{{ expandedPost.favoriteCount }}</strong>
                <span>收藏</span>
              </button>
            </div>

            <div v-if="showPostCommentBox" class="reply-box mt-4">
              <textarea v-model="newCommentText" maxlength="200" placeholder="写一条评论，最多 200 字"></textarea>
              <div class="mt-2 flex items-center justify-between">
                <span class="text-xs font-bold text-[#718097]">{{ newCommentText.length }}/200</span>
                <button class="reply-submit" @click="submitPostComment">
                  <Send :size="15" />
                  回复
                </button>
              </div>
            </div>

            <div class="mt-6">
              <h2 class="text-xl font-black text-[#19202f]">评论区</h2>
              <div class="mt-3 space-y-3">
                <article v-for="comment in rootDetailComments" :key="comment.id" class="comment-card">
                  <div class="flex items-center gap-2">
                    <button class="author-tap" @click="openAuthorProfile(comment.author)">
                      <img :src="comment.author.avatarUrl || fallbackAvatar(comment.author.nickname)" alt="" class="h-8 w-8 rounded-full" />
                    </button>
                    <p class="text-sm font-black">{{ comment.author.nickname }}</p>
                    <span class="text-xs font-bold text-[#718097]">Lv.{{ comment.author.level }} {{ levelTitleFor(comment.author.level) }}</span>
                  </div>
                  <p class="mt-2 text-sm leading-6 text-[#596579]">{{ comment.content }}</p>
                  <div class="comment-footer">
                    <div class="flex items-center gap-2">
                      <button class="comment-action" :class="comment.liked ? 'comment-action--active' : ''" @click="toggleCommentLike(comment)">
                        <Heart :size="14" :fill="comment.liked ? 'currentColor' : 'none'" />
                        {{ comment.likeCount }}
                      </button>
                      <button class="comment-action" @click="startReply(comment)">
                        <Reply :size="14" />
                        回复
                      </button>
                    </div>
                    <time>{{ formatPublishedAt(comment.createdAt) }}</time>
                  </div>

                  <div v-if="replyTargetId === comment.id" class="reply-box reply-box--compact mt-3">
                    <textarea v-model="replyText" maxlength="200" :placeholder="`回复 ${comment.author.nickname}`"></textarea>
                    <div class="mt-2 flex items-center justify-between">
                      <span class="text-xs font-bold text-[#718097]">{{ replyText.length }}/200</span>
                      <button class="reply-submit" @click="submitReply(comment)">
                        <Send :size="15" />
                        回复
                      </button>
                    </div>
                  </div>

                  <div v-if="visibleReplies(comment).length" class="reply-list">
                    <article v-for="reply in visibleReplies(comment)" :key="reply.id" class="reply-bubble">
                      <div class="flex items-center gap-2">
                        <button class="author-tap" @click="openAuthorProfile(reply.author)">
                          <img :src="reply.author.avatarUrl || fallbackAvatar(reply.author.nickname)" alt="" class="h-6 w-6 rounded-full" />
                        </button>
                        <p class="text-xs font-black">{{ reply.author.nickname }}</p>
                        <span class="text-[11px] font-bold text-[#718097]">Lv.{{ reply.author.level }} {{ levelTitleFor(reply.author.level) }}</span>
                      </div>
                      <p class="mt-1 text-sm leading-6 text-[#596579]">{{ reply.content }}</p>
                      <div class="comment-footer">
                        <div class="flex items-center gap-2">
                          <button class="comment-action" :class="reply.liked ? 'comment-action--active' : ''" @click="toggleCommentLike(reply)">
                            <Heart :size="13" :fill="reply.liked ? 'currentColor' : 'none'" />
                            {{ reply.likeCount }}
                          </button>
                        </div>
                        <time>{{ formatPublishedAt(reply.createdAt) }}</time>
                      </div>
                    </article>
                  </div>

                  <button v-if="remainingReplyCount(comment) > 0 || (repliesFor(comment).length > 0 && !expandedReplyCount(comment))" class="expand-replies" @click="expandReplies(comment)">
                    {{ expandedReplyCount(comment) ? `继续展开 ${remainingReplyCount(comment)} 条` : `展开回复 ${repliesFor(comment).length} 条` }}
                  </button>
                </article>
              </div>
            </div>
          </div>
        </article>
      </div>
    </Transition>

    <Transition name="detail-pop">
      <div v-if="editDraft" class="fixed inset-0 z-50 grid place-items-center bg-[#0f172a]/34 p-4 backdrop-blur-sm" @click.self="editDraft = null">
        <section class="w-[min(560px,calc(100vw-32px))] rounded-[30px] bg-white p-6 shadow-[0_30px_120px_rgba(15,23,42,.28)]">
          <div class="flex items-center justify-between">
            <h2 class="text-2xl font-black text-[#19202f]">编辑课程</h2>
            <button class="icon-button" @click="editDraft = null"><X :size="18" /></button>
          </div>
          <div class="mt-5 grid gap-3">
            <input v-model="editDraft.title" class="field" placeholder="课程名" />
            <div class="grid grid-cols-2 gap-3">
              <input v-model="editDraft.start" class="field" placeholder="开始时间" />
              <input v-model="editDraft.end" class="field" placeholder="结束时间" />
            </div>
            <div class="grid grid-cols-2 gap-3">
              <label class="grid gap-2">
                <span class="text-xs font-black text-[#718097]">起始周</span>
                <input v-model.number="editDraft.startWeek" class="field" min="1" :max="maxTermWeek" type="number" />
              </label>
              <label class="grid gap-2">
                <span class="text-xs font-black text-[#718097]">结束周</span>
                <input v-model.number="editDraft.endWeek" class="field" min="1" :max="maxTermWeek" type="number" />
              </label>
            </div>
            <input v-model="editDraft.location" class="field" placeholder="教室" />
            <input v-model="editDraft.teacher" class="field" placeholder="老师" />
            <button class="rounded-full bg-[#19202f] px-5 py-3 text-sm font-black text-white" @click="saveCourse">保存课程</button>
          </div>
        </section>
      </div>
    </Transition>

    <Transition name="detail-pop">
      <div v-if="profileDraft" class="fixed inset-0 z-50 grid place-items-center bg-[#0f172a]/34 p-4 backdrop-blur-sm" @click.self="closeProfileEditor">
        <section class="w-[min(560px,calc(100vw-32px))] rounded-[30px] bg-white p-6 shadow-[0_30px_120px_rgba(15,23,42,.28)]">
          <div class="flex items-center justify-between">
            <h2 class="text-2xl font-black text-[#19202f]">编辑资料</h2>
            <button class="icon-button" @click="closeProfileEditor"><X :size="18" /></button>
          </div>

          <div class="mt-6 flex items-center gap-4">
            <label class="group relative block h-24 w-24 shrink-0 cursor-pointer overflow-hidden rounded-[28px] border-4 border-[#f0f4fa] bg-[#f6f8fb] shadow-lg">
              <img :src="profileAvatarPreviewUrl || profileDraft.avatarUrl || fallbackAvatar(profileDraft.nickname)" alt="" class="h-full w-full object-cover transition group-hover:scale-105" />
              <span class="absolute inset-0 grid place-items-center bg-[#19202f]/34 text-white opacity-0 transition group-hover:opacity-100">
                <Upload :size="22" />
              </span>
              <span v-if="profileAvatarUploading" class="absolute inset-x-0 bottom-0 bg-[#19202f]/72 py-1 text-center text-[11px] font-black text-white">上传中</span>
              <input class="sr-only" type="file" accept="image/*" @change="handleAvatarFile" />
            </label>
            <div class="min-w-0">
              <p class="truncate text-lg font-black text-[#19202f]">{{ profileDraft.nickname || 'Lucas同学' }}</p>
              <p class="mt-1 text-sm font-bold text-[#718097]">UID {{ profileDraft.publicUid }} · {{ levelTitleFor(currentUser.level) }}</p>
              <p v-if="profileAvatarUploadError" class="mt-2 text-xs font-black text-[#ff3b30]">{{ profileAvatarUploadError }}</p>
            </div>
          </div>

          <div class="mt-5 grid gap-3">
            <label class="grid gap-2">
              <span class="text-xs font-black text-[#718097]">名称</span>
              <input v-model="profileDraft.nickname" class="field" :class="{ 'field--error': Boolean(profileNicknameError) }" maxlength="24" placeholder="输入你的名称" />
              <span v-if="profileNicknameError" class="text-xs font-black text-[#ff3b30]">{{ profileNicknameError }}</span>
            </label>
            <label class="grid gap-2">
              <span class="text-xs font-black text-[#718097]">简介</span>
              <textarea v-model="profileDraft.bio" class="field min-h-24 resize-y" maxlength="120" placeholder="写一句个人简介"></textarea>
            </label>
            <label class="grid gap-2">
              <span class="text-xs font-black text-[#718097]">座右铭</span>
              <input v-model="profileDraft.motto" class="field" maxlength="30" placeholder="30 字以内的座右铭" />
            </label>
            <button class="rounded-full bg-[#19202f] px-5 py-3 text-sm font-black text-white disabled:cursor-not-allowed disabled:opacity-60" :disabled="profileAvatarUploading || profileNicknameChecking" @click="saveProfile">
              {{ profileAvatarUploading ? '头像上传中...' : profileNicknameChecking ? '校验中...' : '保存资料' }}
            </button>
          </div>
        </section>
      </div>
    </Transition>

    <Transition name="detail-pop">
      <div v-if="avatarCropOpen" class="fixed inset-0 z-[65] grid place-items-center bg-[#0f172a]/42 p-4 backdrop-blur-sm" @click.self="cancelAvatarCrop">
        <section class="avatar-crop-panel">
          <div class="flex items-start justify-between gap-3">
            <div>
              <p class="text-xs font-black uppercase text-[#718097]">头像裁剪</p>
              <h2 class="mt-1 text-2xl font-black text-[#19202f]">选择正方形区域</h2>
            </div>
            <button class="icon-button" @click="cancelAvatarCrop"><X :size="18" /></button>
          </div>
          <div
            ref="avatarCropFrame"
            class="avatar-crop-frame mt-5"
            @pointerdown="startAvatarCropDrag"
            @pointermove="moveAvatarCropDrag"
            @pointerup="endAvatarCropDrag"
            @pointercancel="endAvatarCropDrag"
            @pointerleave="endAvatarCropDrag"
          >
            <img
              v-if="avatarCropImageUrl"
              :src="avatarCropImageUrl"
              alt=""
              class="avatar-crop-image"
              :style="avatarCropImageStyle"
              draggable="false"
            />
            <div class="avatar-crop-mask"></div>
            <div class="avatar-crop-box"></div>
          </div>
          <label class="avatar-crop-control mt-5">
            <span>缩放</span>
            <input v-model.number="avatarCropScale" type="range" :min="avatarCropMinScale" :max="avatarCropMaxScale" step="0.01" @input="normalizeAvatarCrop" />
          </label>
          <div class="mt-5 grid grid-cols-2 gap-3">
            <button class="avatar-crop-button avatar-crop-button--ghost" @click="cancelAvatarCrop">取消</button>
            <button class="avatar-crop-button" :disabled="profileAvatarUploading" @click="confirmAvatarCrop">
              {{ profileAvatarUploading ? '上传中...' : '确认裁剪' }}
            </button>
          </div>
          <p v-if="profileAvatarUploadError" class="mt-3 text-xs font-black text-[#ff3b30]">{{ profileAvatarUploadError }}</p>
        </section>
      </div>
    </Transition>

    <Transition name="detail-pop">
      <div v-if="authNotice" class="auth-notice" :class="`auth-notice--${authNoticeTone}`">
        {{ authNotice }}
      </div>
    </Transition>

    <Transition name="drawer-fade">
      <div v-if="accountDrawerOpen" class="fixed inset-0 z-[62] bg-[#0f172a]/28 backdrop-blur-sm" @click.self="accountDrawerOpen = false">
        <aside class="account-drawer">
          <div class="flex items-start justify-between gap-3">
            <div class="min-w-0">
              <p class="text-xs font-black uppercase text-[#718097]">菜单</p>
              <h2 class="mt-1 text-2xl font-black text-[#19202f]">{{ isAuthenticated ? currentUser.nickname : '未登录' }}</h2>
              <p class="mt-1 truncate text-sm font-bold text-[#718097]">{{ isAuthenticated ? `UID ${currentUser.publicUid}` : '登录后可使用账号功能' }}</p>
            </div>
            <button class="icon-button" @click="accountDrawerOpen = false"><X :size="18" /></button>
          </div>

          <div class="mt-6 grid gap-3">
            <button class="drawer-action" @click="openAccountCenter">
              <UserRound :size="20" />
              <span>
                <strong>账号中心</strong>
                <small>资料、安全和绑定入口</small>
              </span>
            </button>
            <button v-if="isAuthenticated" class="drawer-action drawer-action--danger" @click="logoutCurrentUser">
              <LogOut :size="20" />
              <span>
                <strong>退出登录</strong>
                <small>注销当前会话并回到首页</small>
              </span>
            </button>
            <button v-else class="drawer-action" @click="openDrawerLogin">
              <LogIn :size="20" />
              <span>
                <strong>登录</strong>
                <small>进入你的校园账号</small>
              </span>
            </button>
          </div>
        </aside>
      </div>
    </Transition>

    <Transition name="detail-pop">
      <div v-if="showLevelCatalog" class="fixed inset-0 z-[55] grid place-items-center bg-[#0f172a]/34 p-4 backdrop-blur-sm" @click.self="showLevelCatalog = false">
        <section class="level-catalog-modal bg-white p-6 shadow-[0_30px_120px_rgba(15,23,42,.28)]">
          <div class="flex items-start justify-between gap-4">
            <div>
              <h2 class="text-2xl font-black text-[#19202f]">等级铭牌</h2>
              <p class="mt-1 text-sm font-bold text-[#718097]">每 10 级一个阶段铭牌</p>
            </div>
            <button class="icon-button" @click="showLevelCatalog = false"><X :size="18" /></button>
          </div>

          <div class="level-catalog-current">
            <button class="level-nameplate" :class="levelBadgeClass(currentUser.level)">
              <strong>Lv.{{ currentUser.level }}</strong>
              <span>{{ levelTitleFor(currentUser.level) }}</span>
            </button>
            <div>
              <p class="text-sm font-black text-[#19202f]">Lv.{{ currentUser.level }} {{ levelTitleFor(currentUser.level) }}</p>
              <p class="mt-1 text-xs font-bold text-[#718097]">距离下一级还需要 {{ Math.max(0, currentLevelNeed - currentUser.xp) }} XP</p>
            </div>
          </div>

          <div class="level-catalog-table thin-scrollbar">
            <article v-for="item in levelCatalog" :key="item.rangeLabel" class="level-catalog-row" :class="item.active ? 'level-catalog-row--current' : ''">
              <button class="level-nameplate level-nameplate--catalog" :class="item.className">
                <strong>{{ item.rangeLabel }}</strong>
                <span>{{ item.title }}</span>
              </button>
              <div>
                <strong>{{ item.active ? '当前阶段' : item.summary }}</strong>
                <small>{{ item.description }}</small>
              </div>
            </article>
          </div>
        </section>
      </div>
    </Transition>

    <Transition name="detail-pop">
      <div v-if="showLoginDialog" class="fixed inset-0 z-[65] grid place-items-center bg-[#0f172a]/34 p-4 backdrop-blur-sm" @click.self="closeAuthDialog">
        <section class="auth-shell" :class="authMode !== 'login' ? 'auth-shell--subview' : ''">
          <div class="auth-shell__grid">
            <aside class="auth-shell__intro">
              <img src="/auth-campus.svg" alt="" class="auth-shell__intro-media" />
              <div class="auth-shell__intro-content">
                <div class="auth-shell__brand flex items-center gap-3">
                  <img src="/favicon.svg" alt="" class="h-14 w-14 rounded-[18px]" />
                  <h2 class="text-3xl font-black tracking-normal text-white">UniPO</h2>
                </div>
                <p class="auth-shell__intro-copy max-w-[24rem] text-sm leading-7 text-white/86">
                  在这里，你可以享受和同好交友的快乐，接收到所有大学期间的重要信息，打破和同龄人的信息差。
                </p>
              </div>
            </aside>

            <div class="auth-shell__panel" :class="authMode !== 'login' ? 'auth-shell__panel--flipped' : ''">
              <div class="auth-shell__panel-inner">
                <section class="auth-face auth-face--front">
                  <button class="auth-close-button icon-button" type="button" @click="closeAuthDialog"><X :size="18" /></button>
                  <div class="auth-face__header auth-face__header--front">
                    <div>
                      <h3 class="auth-face__title">登录</h3>
                      <p class="auth-face__subtitle">欢迎回来</p>
                    </div>
                  </div>

                  <form class="mt-6 grid gap-4" @submit.prevent="loginWithDraft">
                    <label class="auth-field">
                      <span>邮箱 / 账号</span>
                      <input
                        v-model="authForms.login.email"
                        class="field auth-field__input"
                        :class="authErrors.login.email ? 'field--error' : ''"
                        placeholder="请输入邮箱或账号"
                        type="text"
                        autocomplete="username"
                      />
                    </label>
                    <label class="auth-field">
                      <span>密码</span>
                      <input
                        v-model="authForms.login.password"
                        class="field auth-field__input"
                        :class="authErrors.login.password ? 'field--error' : ''"
                        placeholder="请输入密码"
                        type="password"
                        autocomplete="current-password"
                      />
                    </label>
                    <button class="auth-primary-button" :disabled="authSubmitting" type="submit">
                      <span v-if="!authSubmitting">登录</span>
                      <span v-else class="auth-loading-dot" aria-label="登录中"></span>
                    </button>
                  </form>

                  <div class="mt-5 flex items-center justify-between gap-3 text-sm">
                    <button class="auth-link" type="button" @click="switchAuthMode('register')">注册账号</button>
                    <button class="auth-link" type="button" @click="switchAuthMode('reset')">忘记密码</button>
                  </div>
                </section>

                <section class="auth-face auth-face--back">
                  <button class="auth-back-button icon-button" type="button" title="返回登录" @click="switchAuthMode('login')">
                    <ChevronLeft :size="18" />
                  </button>
                  <div class="auth-face__right-actions">
                    <div class="auth-tab-row" :class="authMode === 'reset' ? 'auth-tab-row--reset' : ''">
                      <button class="auth-tab" :class="authMode === 'register' ? 'auth-tab--active' : ''" type="button" @click="switchAuthMode('register')">注册账号</button>
                      <button class="auth-tab" :class="authMode === 'reset' ? 'auth-tab--active' : ''" type="button" @click="switchAuthMode('reset')">找回密码</button>
                    </div>
                    <button class="auth-close-button auth-close-button--inline icon-button" type="button" @click="closeAuthDialog"><X :size="18" /></button>
                  </div>

                  <div class="auth-face__header auth-face__header--back" aria-hidden="true"></div>

                  <div class="auth-form-slider" :class="authMode === 'reset' ? 'auth-form-slider--reset' : ''">
                    <div class="auth-form-track">
                      <form
                        class="auth-form-page grid gap-3"
                        :class="authMode === 'register' ? 'auth-form-page--active' : ''"
                        :aria-hidden="authMode !== 'register'"
                        :inert="authMode !== 'register'"
                        @submit.prevent="registerWithDraft"
                      >
                        <label class="auth-field">
                          <span>用户名</span>
                          <input
                            v-model="authForms.register.nickname"
                            class="field auth-field__input"
                            :class="authErrors.register.nickname ? 'field--error' : ''"
                            placeholder="请输入用户名"
                            type="text"
                            autocomplete="nickname"
                            @blur="checkRegisterNickname"
                          />
                        </label>
                        <label class="auth-field">
                          <span>绑定邮箱</span>
                          <div class="auth-split-row">
                            <input
                              v-model="authForms.register.email"
                              class="field auth-field__input"
                              :class="authErrors.register.email ? 'field--error' : ''"
                              placeholder="请输入邮箱"
                              type="email"
                              autocomplete="email"
                            />
                            <button class="auth-code-button" type="button" :disabled="verificationCodeSending || registerCodeCountdown > 0" @click="sendVerificationCode('register')">
                              <span v-if="registerCodeCountdown > 0">{{ registerCodeCountdown }}s</span>
                              <span v-else-if="!verificationCodeSending">发送验证码</span>
                              <span v-else class="auth-loading-dot auth-loading-dot--sm" aria-label="发送中"></span>
                            </button>
                          </div>
                        </label>
                        <label class="auth-field">
                          <span>验证码</span>
                          <input
                            v-model="authForms.register.verificationCode"
                            class="field auth-field__input"
                            :class="authErrors.register.verificationCode ? 'field--error' : ''"
                            placeholder="请输入 4 位验证码"
                            inputmode="numeric"
                            maxlength="4"
                            type="text"
                            autocomplete="one-time-code"
                          />
                        </label>
                        <label class="auth-field">
                          <span>密码</span>
                          <input
                            v-model="authForms.register.password"
                            class="field auth-field__input"
                            :class="authErrors.register.password ? 'field--error' : ''"
                            placeholder="至少 6 位"
                            type="password"
                            autocomplete="new-password"
                          />
                        </label>
                        <label class="auth-field">
                          <span>确认密码</span>
                          <input
                            v-model="authForms.register.confirmPassword"
                            class="field auth-field__input"
                            :class="authErrors.register.confirmPassword ? 'field--error' : ''"
                            placeholder="再次输入密码"
                            type="password"
                            autocomplete="new-password"
                          />
                        </label>
                        <button class="auth-primary-button" :disabled="authSubmitting" type="submit">
                          <span v-if="!authSubmitting">注册并登录</span>
                          <span v-else class="auth-loading-dot" aria-label="提交中"></span>
                        </button>
                      </form>

                      <form
                        class="auth-form-page grid gap-3"
                        :class="authMode === 'reset' ? 'auth-form-page--active' : ''"
                        :aria-hidden="authMode !== 'reset'"
                        :inert="authMode !== 'reset'"
                        @submit.prevent="resetPasswordWithDraft"
                      >
                        <label class="auth-field">
                          <span>绑定邮箱</span>
                          <div class="auth-split-row">
                            <input
                              v-model="authForms.reset.email"
                              class="field auth-field__input"
                              :class="authErrors.reset.email ? 'field--error' : ''"
                              placeholder="请输入邮箱"
                              type="email"
                              autocomplete="email"
                            />
                            <button class="auth-code-button" type="button" :disabled="verificationCodeSending || resetCodeCountdown > 0" @click="sendVerificationCode('reset')">
                              <span v-if="resetCodeCountdown > 0">{{ resetCodeCountdown }}s</span>
                              <span v-else-if="!verificationCodeSending">发送验证码</span>
                              <span v-else class="auth-loading-dot auth-loading-dot--sm" aria-label="发送中"></span>
                            </button>
                          </div>
                        </label>
                        <label class="auth-field">
                          <span>验证码</span>
                          <input
                            v-model="authForms.reset.verificationCode"
                            class="field auth-field__input"
                            :class="authErrors.reset.verificationCode ? 'field--error' : ''"
                            placeholder="请输入 4 位验证码"
                            inputmode="numeric"
                            maxlength="4"
                            type="text"
                            autocomplete="one-time-code"
                          />
                        </label>
                        <label class="auth-field">
                          <span>新密码</span>
                          <input
                            v-model="authForms.reset.password"
                            class="field auth-field__input"
                            :class="authErrors.reset.password ? 'field--error' : ''"
                            placeholder="至少 6 位"
                            type="password"
                            autocomplete="new-password"
                          />
                        </label>
                        <label class="auth-field">
                          <span>确认新密码</span>
                          <input
                            v-model="authForms.reset.confirmPassword"
                            class="field auth-field__input"
                            :class="authErrors.reset.confirmPassword ? 'field--error' : ''"
                            placeholder="再次输入新密码"
                            type="password"
                            autocomplete="new-password"
                          />
                        </label>
                        <button class="auth-primary-button" :disabled="authSubmitting" type="submit">
                          <span v-if="!authSubmitting">重置密码</span>
                          <span v-else class="auth-loading-dot" aria-label="提交中"></span>
                        </button>
                      </form>
                    </div>
                  </div>
                </section>
              </div>
            </div>
          </div>
        </section>
      </div>
    </Transition>
  </div>
</template>

<script setup lang="ts">
import { computed, defineComponent, h, nextTick, onBeforeUnmount, onMounted, ref, watch, type Component } from 'vue';
import dayjs from 'dayjs';
import { AtSign, Bell, Bold, Bookmark, Building2, CalendarDays, CheckCircle2, ChevronLeft, ChevronRight, Edit3, Flame, GraduationCap, Heart, Home, ImagePlus, ListPlus, LogIn, LogOut, Menu, MessageCircle, Palette, PlusCircle, Reply, School, Search, Send, Trash2, Type, Upload, UserPlus, UserRound, X } from 'lucide-vue-next';
import { apiErrorMessage, authApi, campusApi, isAuthError, mediaApi, postApi, userApi } from './lib/api';
import type { AuthorView, BoardCode, BoardView, CheckInView, CommentView, ConversationView, InteractionNoticeView, MessageView, PostView, PublicProfileView, UserProfile, UserStats } from './types';

type ViewKey = 'home' | 'schedule' | 'compose' | 'messages' | 'profile' | 'user-profile';
type MessageTabKey = 'likes' | 'favorites' | 'comments' | 'followers';
type ProfileTabKey = 'posts' | 'likes' | 'favorites';

interface ProfileDraft {
  id: number;
  publicUid: string;
  nickname: string;
  avatarUrl: string;
  bio: string;
  motto: string;
  levelTitle: string;
  grade?: string;
}

interface ComposeDraft {
  board: Exclude<BoardCode, 'recommend'>;
  title: string;
  content: string;
  coverUrl: string;
  images: string[];
}

interface CampusPost extends PostView {
  last2hLikes?: number;
  last2hComments?: number;
  eventDate?: string;
}

interface CampusComment extends CommentView {}

interface CampusMessage extends MessageView {
  optimistic?: boolean;
}

interface SpotlightItem {
  id: number;
  title: string;
  kicker: string;
  summary: string;
  badge: string;
  imageUrl: string;
  adminOnly: boolean;
}

interface Course {
  id: number;
  day: number;
  title: string;
  start: string;
  end: string;
  location: string;
  teacher: string;
  color: string;
  scope: string;
  startWeek: number;
  endWeek: number;
  seriesId: number;
}

interface CalendarDay {
  dateKey: string;
  day: string;
  inMonth: boolean;
  postCount: number;
}

interface NoticeItem {
  id: string;
  type: MessageTabKey;
  actor: CampusPost['author'];
  text: string;
  post?: CampusPost;
  coverUrl?: string;
  commentContent?: string;
  commentPrefix?: string;
  createdAt: string;
  unread: boolean;
}

interface OperatorAnnouncement {
  id: number;
  badge: string;
  title: string;
  summary: string;
}

const Metric = defineComponent({
  props: {
    label: { type: String, required: true },
    value: { type: Number, required: true }
  },
  setup(props) {
    return () => h('div', { class: 'rounded-[22px] bg-white/68 p-4 text-center' }, [
      h('p', { class: 'text-2xl font-black text-[#19202f]' }, String(props.value)),
      h('p', { class: 'mt-1 text-xs font-bold text-[#718097]' }, props.label)
    ]);
  }
});

const activeView = ref<ViewKey>('home');
const activeBoard = ref<BoardCode>('recommend');
const selectedDate = ref<string | null>(null);
const feedPage = ref(1);
const posts = ref<CampusPost[]>(fallbackPosts());
const boards = ref<BoardView[]>(fallbackBoards());
const comments = ref<CampusComment[]>(fallbackComments());
const interactionNotices = ref<InteractionNoticeView[]>([]);
const conversations = ref<ConversationView[]>([]);
const conversationMessages = ref<Record<number, CampusMessage[]>>({});
const profilePosts = ref<CampusPost[]>([]);
const profileLikedPosts = ref<CampusPost[]>([]);
const profileFavoritePosts = ref<CampusPost[]>([]);
const viewedProfile = ref<PublicProfileView | null>(null);
const viewedProfilePosts = ref<CampusPost[]>([]);
const viewedProfileLikedPosts = ref<CampusPost[]>([]);
const viewedProfileFavoritePosts = ref<CampusPost[]>([]);
const unreadCount = ref(0);
const profileTab = ref<ProfileTabKey>('posts');
const viewedProfileTab = ref<ProfileTabKey>('posts');
const currentUser = ref<UserProfile>(fallbackUser());
const userStats = ref<UserStats>(fallbackStats());
const selectedPost = ref<CampusPost | null>(posts.value[0] || null);
const expandedPost = ref<CampusPost | null>(null);
const spotlightIndex = ref(0);
const checkedInToday = ref(false);
const checkinStreak = ref(0);
const checkingIn = ref(false);
const checkinCelebrating = ref(false);
const lastCheckinXp = ref(0);
const importNotice = ref(false);
const courses = ref<Course[]>(fallbackCourses());
const editDraft = ref<Course | null>(null);
const profileDraft = ref<ProfileDraft | null>(null);
const selectedWeek = ref(12);
const activeMessageTab = ref<MessageTabKey>('likes');
const readMessageNoticeIds = ref<Set<string>>(new Set());
const showPostCommentBox = ref(false);
const newCommentText = ref('');
const replyTargetId = ref<number | null>(null);
const replyText = ref('');
const expandedReplyCounts = ref<Record<number, number>>({});
const activeConversationId = ref<number | null>(null);
const messageInput = ref('');
const courseIdSeed = ref(1000);
const authNotice = ref('');
const authNoticeTone = ref<'neutral' | 'error'>('neutral');
const showLoginDialog = ref(false);
const authMode = ref<'login' | 'register' | 'reset'>('login');
const authSubmitting = ref(false);
const verificationCodeSending = ref(false);
const registerCodeCountdown = ref(0);
const resetCodeCountdown = ref(0);
const showLevelCatalog = ref(false);
const accountDrawerOpen = ref(false);
const profileAvatarPreviewUrl = ref('');
const profileAvatarUploading = ref(false);
const profileAvatarUploadError = ref('');
const profileNicknameChecking = ref(false);
const profileNicknameError = ref('');
const deletingPostIds = ref<Set<number>>(new Set());
const avatarCropOpen = ref(false);
const avatarCropImageUrl = ref('');
const avatarCropSourceFile = ref<File | null>(null);
const avatarCropFrame = ref<HTMLElement | null>(null);
const avatarCropNaturalWidth = ref(0);
const avatarCropNaturalHeight = ref(0);
const avatarCropScale = ref(1);
const avatarCropMinScale = ref(1);
const avatarCropMaxScale = ref(4);
const avatarCropOffsetX = ref(0);
const avatarCropOffsetY = ref(0);
const postImagePreviewUrl = ref('');
const postContentInput = ref<HTMLElement | null>(null);
const composeErrors = ref({ board: '', title: '', content: '' });
const isAuthenticated = ref(Boolean(localStorage.getItem('bcg_token')));
const authForms = ref({
  login: {
    email: '',
    password: ''
  },
  register: {
    nickname: '',
    email: '',
    verificationCode: '',
    password: '',
    confirmPassword: ''
  },
  reset: {
    email: '',
    verificationCode: '',
    password: '',
    confirmPassword: ''
  }
});
let draftSavedRange: Range | null = null;
const authErrors = ref({
  login: {
    email: '',
    password: ''
  },
  register: {
    nickname: '',
    email: '',
    verificationCode: '',
    password: '',
    confirmPassword: ''
  },
  reset: {
    email: '',
    verificationCode: '',
    password: '',
    confirmPassword: ''
  }
});
const todayKey = dayjs().format('YYYY-MM-DD');
const readMessageNoticeStoragePrefix = 'bcg_read_message_notices';
let spotlightTimer: number | undefined;
let conversationListTimer: number | undefined;
let activeConversationTimer: number | undefined;
let presenceHeartbeatTimer: number | undefined;
let announcementCarouselTimer: number | undefined;
let registerCodeCountdownTimer: number | undefined;
let resetCodeCountdownTimer: number | undefined;
let nicknameAvailabilityToken = 0;
let profileNicknameAvailabilityToken = 0;
let profileAvatarUploadToken = 0;
let profileAvatarUploadPromise: Promise<string | null> | null = null;
let avatarCropDragState: { pointerId: number; startX: number; startY: number; originX: number; originY: number } | null = null;
let refreshingConversations = false;
let refreshingActiveConversation = false;
let activePostOpenToken = 0;

const draft = ref<ComposeDraft>({
  board: 'school',
  title: '',
  content: '',
  coverUrl: '',
  images: []
});

const composeBoardOptions = [
  { value: 'school', label: '校 PO', description: '全校同学可见', icon: School },
  { value: 'college', label: '院 PO', description: '学院范围同步', icon: Building2 },
  { value: 'major', label: '专业 PO', description: '专业同学优先', icon: GraduationCap }
] satisfies Array<{ value: ComposeDraft['board']; label: string; description: string; icon: Component }>;

const editorEmojis = ['👍', '😭', '🔥', '✨', '🙏', '👀', '✅', '💡'];
const editorColors = [
  { label: '黑色正文', value: '#19202f' },
  { label: '蓝色重点', value: '#007aff' },
  { label: '红色提醒', value: '#ff3b30' },
  { label: '绿色通过', value: '#22c55e' },
  { label: '紫色标记', value: '#7c3aed' }
];
const announcementCarouselIndex = ref(0);
const announcementCarouselTransition = ref(true);
const announcementCarouselStep = 86;

const operatorAnnouncements: OperatorAnnouncement[] = [
  {
    id: 1,
    badge: '公告',
    title: '新版首页运营位正在灰度调整',
    summary: '右侧公告区后续会同步网站运营发布的规则、活动和功能更新。'
  },
  {
    id: 2,
    badge: '提醒',
    title: '内容反馈入口即将接入后台',
    summary: '举报、置顶、公告配置将逐步进入运营后台统一维护。'
  },
  {
    id: 3,
    badge: '规则',
    title: '校园 PO 发布规范本周更新',
    summary: '标题、图片、活动时间和联系方式将增加更清晰的审核提示。'
  },
  {
    id: 4,
    badge: '活动',
    title: '优质避坑 PO 将进入首页推荐池',
    summary: '被同学收藏、评论补充的信息会优先进入运营巡检列表。'
  },
  {
    id: 5,
    badge: '维护',
    title: '对象存储图片服务已切换到私有桶',
    summary: '站内图片会通过平台接口读取，外部不会直接暴露存储桶地址。'
  }
];

const dockItems = [
  { key: 'home', label: '首页', icon: Home },
  { key: 'schedule', label: '课程表', icon: CalendarDays },
  { key: 'compose', label: '发PO', icon: PlusCircle },
  { key: 'messages', label: '消息', icon: Bell },
  { key: 'profile', label: '我的', icon: UserRound }
] satisfies Array<{ key: ViewKey; label: string; icon: Component }>;

const messageTabs = [
  { key: 'likes', label: '点赞我的', icon: Heart },
  { key: 'favorites', label: '收藏我的', icon: Bookmark },
  { key: 'comments', label: '评论我的', icon: MessageCircle },
  { key: 'followers', label: '关注我的', icon: UserPlus }
] satisfies Array<{ key: MessageTabKey; label: string; icon: Component }>;

const calendarWeekLabels = ['日', '一', '二', '三', '四', '五', '六'];
const profileBanner = 'https://images.unsplash.com/photo-1503676260728-1c00da094a0b?auto=format&fit=crop&w=1400&q=80';
const maxLevel = 100;
const maxTermWeek = 20;
const termStart = dayjs('2026-02-16');
const levelTiers = [
  '星途引路人',
  '食域裁决官',
  '万社登临者',
  '书渊夜帝君',
  '课业天机主',
  '实训执掌者',
  '百味揽星客',
  '绩点造化尊',
  '学海无双尊',
  '校园万古名'
];
const levelTierDescriptions = [
  '刚进入校园 PO 的新同学，先从稳定发布和互动开始。',
  '开始形成自己的信息雷达，能帮同学避开小坑。',
  '活跃在多个社团和校园场景，信息覆盖更广。',
  '资料、复盘和学习线索贡献明显增多。',
  '对课程和作业节奏更敏感，能给出可执行建议。',
  '项目、实训、竞赛方向的经验开始沉淀。',
  '生活服务、美食、校园路线都能补充可靠信息。',
  '升学、成绩、选课相关内容更有参考价值。',
  '已经是校园信息网络里的高可信贡献者。',
  '顶级铭牌阶段，校园 PO 里的标志性账号。'
];

const dailyQuotes = [
  { text: '今天先把最小的一件事做完，心里会亮一点。', source: '校园治愈推送' },
  { text: '不需要一直满格电量，低电量也可以慢慢往前。', source: 'UniPO' },
  { text: '你已经在路上了，慢一点也不算掉队。', source: '后台素材 Mock' }
];

const spotlightItems = ref<SpotlightItem[]>([
  {
    id: 1,
    title: '今天学校里，真正有用的事。',
    kicker: '试点大学的校园 PO 流',
    summary: '这不是 PO 文列表，而是首页宣传位，切到校 PO、院 PO、专业 PO 后自动收起。',
    badge: '管理员配置',
    imageUrl: 'https://images.unsplash.com/photo-1524995997946-a1c2e315a42f?auto=format&fit=crop&w=1400&q=80',
    adminOnly: true
  },
  {
    id: 2,
    title: '期末周自习位实时更新',
    kicker: '图书馆与教学楼联动',
    summary: '为广告和重点事件预留的轮播位，后续由管理员在管理后台维护。',
    badge: '校园焦点',
    imageUrl: 'https://images.unsplash.com/photo-1497366811353-6870744d04b2?auto=format&fit=crop&w=1400&q=80',
    adminOnly: true
  },
  {
    id: 3,
    title: '讲座、报名、比赛集中看',
    kicker: '别让关键节点埋在群聊里',
    summary: '公告、报名、比赛集中展示，点击具体日期后中间 PO 流自动切到当天。',
    badge: '重点事件',
    imageUrl: 'https://images.unsplash.com/photo-1515187029135-18ee286d815b?auto=format&fit=crop&w=1400&q=80',
    adminOnly: true
  }
]);

const activeBoardIndex = computed(() => Math.max(0, boards.value.findIndex((item) => item.code === activeBoard.value)));
const activeDockIndex = computed(() => Math.max(0, dockItems.findIndex((item) => item.key === activeView.value)));
const activeMessageTabIndex = computed(() => Math.max(0, messageTabs.findIndex((item) => item.key === activeMessageTab.value)));
const activeSpotlight = computed(() => spotlightItems.value[spotlightIndex.value % spotlightItems.value.length]);
const showSpotlight = computed(() => activeBoard.value === 'recommend' && !selectedDate.value);
const dailyQuote = computed(() => dailyQuotes[dayjs().date() % dailyQuotes.length]);
const feedPageSize = 10;

const filteredPosts = computed(() => posts.value
  .filter((post) => {
    const dateMatches = selectedDate.value ? postDateKey(post) === selectedDate.value : true;
    return dateMatches && boardMatches(post);
  })
  .sort((a, b) => recommendScore(b) - recommendScore(a)));
const feedPageCount = computed(() => Math.max(1, Math.ceil(filteredPosts.value.length / feedPageSize)));
const pagedPosts = computed(() => filteredPosts.value.slice((feedPage.value - 1) * feedPageSize, feedPage.value * feedPageSize));
const canGoPrevFeedPage = computed(() => feedPage.value > 1);
const canGoNextFeedPage = computed(() => feedPage.value < feedPageCount.value);

watch([filteredPosts, activeBoard, selectedDate], () => {
  feedPage.value = 1;
});

watch(feedPageCount, (count) => {
  if (feedPage.value > count) feedPage.value = count;
});

const hotRankings = computed(() => posts.value
  .slice()
  .sort((a, b) => hotScore(b) - hotScore(a))
  .slice(0, 10));

const calendarBaseDate = computed(() => dayjs(selectedDate.value || '2026-05-03'));
const calendarDays = computed<CalendarDay[]>(() => {
  const start = calendarBaseDate.value.startOf('month').startOf('week');
  return Array.from({ length: 42 }, (_, index) => {
    const date = start.add(index, 'day');
    const dateKey = date.format('YYYY-MM-DD');
    return {
      dateKey,
      day: date.format('D'),
      inMonth: date.month() === calendarBaseDate.value.month(),
      postCount: postsForDate(dateKey).length
    };
  });
});

const selectedCalendarPosts = computed(() => postsForDate(selectedDate.value || dayjs().format('YYYY-MM-DD')).slice(0, 4));
const selectedDateTitle = computed(() => selectedDate.value ? dayjs(selectedDate.value).format('M月D日') : '全部日期');
const profileMotto = computed(() => extractProfileMotto(currentUser.value.bio));
const profileTabs = computed(() => [
  { key: 'posts' as ProfileTabKey, label: '作品', count: userStats.value.posts },
  { key: 'likes' as ProfileTabKey, label: '喜欢', count: userStats.value.likedPosts },
  { key: 'favorites' as ProfileTabKey, label: '收藏', count: userStats.value.favorites }
]);
const activeProfileTabIndex = computed(() => Math.max(0, profileTabs.value.findIndex((tab) => tab.key === profileTab.value)));
const profileGridPosts = computed(() => {
  if (profileTab.value === 'likes') return profileLikedPosts.value;
  if (profileTab.value === 'favorites') return profileFavoritePosts.value;
  return profilePosts.value;
});
const viewedProfileTabs = computed(() => {
  const stats = viewedProfile.value?.stats || fallbackStats();
  return [
    { key: 'posts' as ProfileTabKey, label: '作品', count: stats.posts },
    { key: 'likes' as ProfileTabKey, label: '喜欢', count: stats.likedPosts },
    { key: 'favorites' as ProfileTabKey, label: '收藏', count: stats.favorites }
  ];
});
const activeViewedProfileTabIndex = computed(() => Math.max(0, viewedProfileTabs.value.findIndex((tab) => tab.key === viewedProfileTab.value)));
const viewedProfileGridPosts = computed(() => {
  if (viewedProfileTab.value === 'likes') return viewedProfileLikedPosts.value;
  if (viewedProfileTab.value === 'favorites') return viewedProfileFavoritePosts.value;
  return viewedProfilePosts.value;
});
const viewedProfileSectionTitle = computed(() => viewedProfileTab.value === 'posts' ? 'TA 的 PO' : viewedProfileTab.value === 'likes' ? 'TA 喜欢的 PO' : 'TA 收藏的 PO');
const viewedProfileSectionCount = computed(() => viewedProfileTabs.value.find((tab) => tab.key === viewedProfileTab.value)?.count || 0);

const detailComments = computed(() => expandedPost.value ? commentsForPost(expandedPost.value) : []);
const rootDetailComments = computed(() => detailComments.value.filter((comment) => !comment.parentId));
const activeConversation = computed(() => activeConversationId.value == null
  ? null
  : conversations.value.find((conversation) => conversation.id === activeConversationId.value) || null);
const activeConversationMessages = computed(() => activeConversationId.value == null
  ? []
  : [...(conversationMessages.value[activeConversationId.value] || [])].sort((a, b) => dayjs(a.createdAt).valueOf() - dayjs(b.createdAt).valueOf()));
const privateConversations = computed(() => conversations.value.filter((conversation) => !isFollowOnlyConversation(conversation)));
const privateUnreadCount = computed(() => privateConversations.value.reduce((sum, conversation) => sum + Math.max(0, conversation.unreadCount || 0), 0));
const latestFollowNotices = computed(() => conversations.value
  .filter((conversation) => isFollowNoticeText(conversation.lastMessage))
  .map((conversation) => ({
    id: `follow-${conversation.id}`,
    type: 'followers' as MessageTabKey,
    actor: conversation.peer,
    text: `${conversation.peer.nickname} 关注了你`,
    createdAt: conversation.lastMessageAt || conversation.updatedAt,
    unread: conversation.unreadCount > 0 && !readMessageNoticeIds.value.has(`follow-${conversation.id}`)
  })));
const messageTabUnreadCounts = computed<Record<MessageTabKey, number>>(() => {
  const counts = { likes: 0, favorites: 0, comments: 0, followers: 0 };
  noticeItems.value.forEach((notice) => {
    if (notice.unread) counts[notice.type] += 1;
  });
  latestFollowNotices.value.forEach((notice) => {
    if (notice.unread) counts.followers += 1;
  });
  return counts;
});
const interactionUnreadCount = computed(() => Object.values(messageTabUnreadCounts.value).reduce((sum, count) => sum + count, 0));
const notificationTotalCount = computed(() => privateUnreadCount.value + interactionUnreadCount.value);
const composeBoardIndex = computed(() => Math.max(0, composeBoardOptions.findIndex((option) => option.value === draft.value.board)));
const draftContentLength = computed(() => plainPostContent(draft.value.content).length);
const draftInlineImageCount = computed(() => inlineImageUrls(draft.value.content).length);
const currentLevelNeed = computed(() => levelXpNeed(currentUser.value.level));
const levelProgress = computed(() => Math.min(100, Math.round((currentUser.value.xp / currentLevelNeed.value) * 100)));
const avatarCropImageStyle = computed(() => ({
  width: `${avatarCropNaturalWidth.value * avatarCropScale.value}px`,
  height: `${avatarCropNaturalHeight.value * avatarCropScale.value}px`,
  transform: `translate(calc(-50% + ${avatarCropOffsetX.value}px), calc(-50% + ${avatarCropOffsetY.value}px))`
}));
const levelCatalog = computed(() => Array.from({ length: Math.ceil(maxLevel / 10) }, (_, index) => {
  const from = index * 10 + 1;
  const to = Math.min(maxLevel, from + 9);
  return {
    rangeLabel: `${from}-${to}`,
    title: levelTiers[index],
    className: levelBadgeClass(from),
    active: currentUser.value.level >= from && currentUser.value.level <= to,
    summary: `Lv.${from} 到 Lv.${to}`,
    description: levelTierDescriptions[index]
  };
}));
const checkinBaseXp = computed(() => xpForLevel(currentUser.value.level));
const checkinReward = computed(() => checkinBaseXp.value + Math.min(60, checkinStreak.value * 3));
const checkinButtonText = computed(() => {
  if (!isAuthenticated.value) return '登录后签到';
  if (checkingIn.value) return '签到中...';
  return checkedInToday.value ? `已签到 · 连续 ${checkinStreak.value} 天` : `今日签到 +${checkinReward.value} XP`;
});
const scheduleDays = computed(() => Array.from({ length: 7 }, (_, index) => {
  const date = termStart.add(selectedWeek.value - 1, 'week').add(index, 'day');
  const names = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
  return { key: index + 1, name: names[index], date: date.format('M/D') };
}));

const noticeItems = computed<NoticeItem[]>(() => interactionNotices.value.map((notice) => {
  const post = enrichApiPosts([notice.post])[0];
  const noticeId = interactionNoticeKey(notice);
  const tab = interactionNoticeTab(notice.type);
  return {
    id: noticeId,
    type: tab,
    actor: notice.actor,
    text: interactionNoticeText(notice.actor.nickname, notice),
    post,
    coverUrl: post.coverUrl || fallbackCover(post.id),
    commentContent: notice.commentContent,
    commentPrefix: tab === 'comments' ? `${notice.actor.nickname}${notice.type === 'reply' ? ' 给你的评论回复：' : ' 给你评论：'}` : undefined,
    createdAt: notice.createdAt,
    unread: Boolean(notice.unread) && !readMessageNoticeIds.value.has(noticeId)
  };
}));
const activeNotices = computed<NoticeItem[]>(() => [
  ...noticeItems.value,
  ...latestFollowNotices.value
].filter((notice) => notice.type === activeMessageTab.value));
const operatorAnnouncementLoop = computed(() => [
  ...operatorAnnouncements,
  ...operatorAnnouncements.slice(0, 2)
].map((notice, index) => ({
  ...notice,
  loopKey: `${notice.id}-${index}`
})));

onMounted(async () => {
  await loadCampus();
  spotlightTimer = window.setInterval(() => {
    spotlightIndex.value = (spotlightIndex.value + 1) % spotlightItems.value.length;
  }, 4200);
  conversationListTimer = window.setInterval(() => {
    refreshConversations();
  }, 3000);
  announcementCarouselTimer = window.setInterval(() => {
    const nextIndex = announcementCarouselIndex.value + 1;
    announcementCarouselTransition.value = true;
    announcementCarouselIndex.value = nextIndex;
    if (nextIndex >= operatorAnnouncements.length) {
      window.setTimeout(() => {
        announcementCarouselTransition.value = false;
        announcementCarouselIndex.value = 0;
        window.setTimeout(() => {
          announcementCarouselTransition.value = true;
        }, 40);
      }, 560);
    }
  }, 5000);
  startPresenceHeartbeat();
});

onBeforeUnmount(() => {
  if (spotlightTimer) window.clearInterval(spotlightTimer);
  if (conversationListTimer) window.clearInterval(conversationListTimer);
  if (announcementCarouselTimer) window.clearInterval(announcementCarouselTimer);
  stopVerificationCountdown('register');
  stopVerificationCountdown('reset');
  clearPostImagePreview();
  clearAvatarCrop();
  stopActiveConversationPolling();
  stopPresenceHeartbeat();
});

async function loadCampus() {
  try {
    const [postList, boardList] = await Promise.all([
      campusApi.posts('recommend'),
      campusApi.boards()
    ]);
    posts.value = postList.length ? enrichApiPosts(postList) : fallbackPosts();
    boards.value = boardList.length ? normalizeBoards(boardList) : fallbackBoards();
    await loadAuthenticatedData();
    selectedPost.value = posts.value[0] || null;
    comments.value = fallbackComments();
  } catch {
    posts.value = fallbackPosts();
    boards.value = fallbackBoards();
    await loadAuthenticatedData();
    comments.value = fallbackComments();
    selectedPost.value = posts.value[0] || null;
  }
}

async function loadAuthenticatedData() {
  if (!localStorage.getItem('bcg_token')) {
    isAuthenticated.value = false;
    currentUser.value = fallbackUser();
    userStats.value = fallbackStats();
    conversations.value = [];
    conversationMessages.value = {};
    profilePosts.value = [];
    profileLikedPosts.value = [];
    profileFavoritePosts.value = [];
    interactionNotices.value = [];
    readMessageNoticeIds.value = new Set();
    resetCheckInState();
    viewedProfile.value = null;
    viewedProfilePosts.value = [];
    viewedProfileLikedPosts.value = [];
    viewedProfileFavoritePosts.value = [];
    unreadCount.value = 0;
    activeConversationId.value = null;
    stopPresenceHeartbeat();
    return;
  }

  let me: UserProfile | null = null;
  try {
    me = await campusApi.me();
  } catch (error) {
    if (isAuthError(error)) {
      localStorage.removeItem('bcg_token');
      isAuthenticated.value = false;
      currentUser.value = fallbackUser();
      userStats.value = fallbackStats();
      conversations.value = [];
      conversationMessages.value = {};
      profilePosts.value = [];
      profileLikedPosts.value = [];
      profileFavoritePosts.value = [];
      interactionNotices.value = [];
      readMessageNoticeIds.value = new Set();
      resetCheckInState();
      viewedProfile.value = null;
      viewedProfilePosts.value = [];
      viewedProfileLikedPosts.value = [];
      viewedProfileFavoritePosts.value = [];
      unreadCount.value = 0;
      activeConversationId.value = null;
      stopPresenceHeartbeat();
    } else {
      isAuthenticated.value = true;
    }
    return;
  }

  isAuthenticated.value = true;
  currentUser.value = me;
  readMessageNoticeIds.value = loadReadMessageNoticeIds(me.id);
  startPresenceHeartbeat();
  const [stats, messageList, unread, myPosts, myLikes, myFavorites, notices, checkIn] = await Promise.all([
    safe(() => campusApi.stats(), fallbackStats()),
    safe(() => campusApi.conversations(), [] as ConversationView[]),
    safe(() => campusApi.unreadCount(), 0),
    safe(() => campusApi.myPosts(), [] as PostView[]),
    safe(() => campusApi.myLikes(), [] as PostView[]),
    safe(() => campusApi.myFavorites(), [] as PostView[]),
    safe(() => campusApi.interactionNotices(), [] as InteractionNoticeView[]),
    safe(() => campusApi.checkInStatus(), null as CheckInView | null)
  ]);
  userStats.value = stats;
  conversations.value = messageList;
  unreadCount.value = unread || messageList.reduce((sum, conversation) => sum + conversation.unreadCount, 0);
  profilePosts.value = enrichApiPosts(myPosts);
  profileLikedPosts.value = enrichApiPosts(myLikes);
  profileFavoritePosts.value = enrichApiPosts(myFavorites);
  interactionNotices.value = notices;
  if (checkIn) applyCheckInView(checkIn);
  markOpenMessageTabRead();
}

async function refreshConversations() {
  if (!isAuthenticated.value || !localStorage.getItem('bcg_token') || refreshingConversations) return;
  refreshingConversations = true;
  try {
    const [messageList, unread, notices] = await Promise.all([
      campusApi.conversations(),
      campusApi.unreadCount(),
      campusApi.interactionNotices()
    ]);
    const activeId = activeConversationId.value;
    conversations.value = messageList.map((conversation) => activeId === conversation.id ? { ...conversation, unreadCount: 0 } : conversation);
    interactionNotices.value = notices;
    markOpenMessageTabRead();
    unreadCount.value = activeId == null
      ? unread
      : Math.max(0, unread - (messageList.find((conversation) => conversation.id === activeId)?.unreadCount || 0));
  } catch {
    // Keep the current local state when a poll races with logout or a dev-server hiccup.
  } finally {
    refreshingConversations = false;
  }
}

async function refreshInteractionNotices() {
  if (!isAuthenticated.value || !localStorage.getItem('bcg_token')) return;
  interactionNotices.value = await safe(() => campusApi.interactionNotices(), interactionNotices.value);
  markOpenMessageTabRead();
}

function readMessageNoticeStorageKey(userId = currentUser.value.id) {
  return `${readMessageNoticeStoragePrefix}_${userId || 'guest'}`;
}

function loadReadMessageNoticeIds(userId: number) {
  try {
    const stored = localStorage.getItem(readMessageNoticeStorageKey(userId));
    const ids = stored ? JSON.parse(stored) : [];
    return new Set(Array.isArray(ids) ? ids.filter((id): id is string => typeof id === 'string') : []);
  } catch {
    return new Set<string>();
  }
}

function saveReadMessageNoticeIds() {
  if (!currentUser.value.id) return;
  localStorage.setItem(readMessageNoticeStorageKey(), JSON.stringify([...readMessageNoticeIds.value].slice(-300)));
}

function interactionNoticeKey(notice: InteractionNoticeView) {
  return `${notice.type}-${notice.id}`;
}

function interactionNoticeTab(type: InteractionNoticeView['type']): MessageTabKey {
  if (type === 'like') return 'likes';
  if (type === 'favorite') return 'favorites';
  return 'comments';
}

function markMessageTabRead(tab: MessageTabKey) {
  const visibleIds = [
    ...noticeItems.value,
    ...latestFollowNotices.value
  ]
    .filter((notice) => notice.type === tab && notice.unread)
    .map((notice) => notice.id);
  if (!visibleIds.length) return;
  readMessageNoticeIds.value = new Set([...readMessageNoticeIds.value, ...visibleIds]);
  saveReadMessageNoticeIds();
}

function markOpenMessageTabRead() {
  if (activeView.value === 'messages' && activeConversationId.value == null) {
    markMessageTabRead(activeMessageTab.value);
  }
}

function startPresenceHeartbeat() {
  stopPresenceHeartbeat();
  if (!localStorage.getItem('bcg_token')) return;
  sendPresenceHeartbeat();
  presenceHeartbeatTimer = window.setInterval(() => {
    sendPresenceHeartbeat();
  }, 10000);
}

function stopPresenceHeartbeat() {
  if (presenceHeartbeatTimer) {
    window.clearInterval(presenceHeartbeatTimer);
    presenceHeartbeatTimer = undefined;
  }
}

async function sendPresenceHeartbeat() {
  if (!isAuthenticated.value || !localStorage.getItem('bcg_token')) return;
  await safe(() => authApi.heartbeat(), false);
}

async function sessionStillValid() {
  if (!localStorage.getItem('bcg_token')) return false;
  try {
    const me = await campusApi.me();
    currentUser.value = me;
    isAuthenticated.value = true;
    return true;
  } catch {
    return false;
  }
}

async function handleAuthSensitiveError(error: unknown, loginMessage: string, fallbackMessage?: string) {
  if (isAuthError(error) && !(await sessionStillValid())) {
    openLoginDialog(loginMessage);
    return true;
  }
  showAuthNotice(apiErrorMessage(error, fallbackMessage || loginMessage), 'error');
  return false;
}

function openProfileView() {
  if (!hasValidSession()) {
    openLoginDialog('请先登录后查看个人中心');
    return;
  }
  activeView.value = 'profile';
}

async function openAuthorProfile(author: AuthorView) {
  if (!hasValidSession()) {
    openLoginDialog('请先登录后查看个人主页');
    return;
  }
  const uid = author.uid;
  if (!uid || uid === '00000000') {
    showAuthNotice('这个作者没有可访问的主页');
    return;
  }
  expandedPost.value = null;
  if (isAuthenticated.value && uid === currentUser.value.publicUid) {
    openProfileView();
    return;
  }
  const fallbackProfile = publicProfileFromAuthor(author);
  viewedProfileTab.value = 'posts';
  const [profile, authorPosts, likedPosts, favoritePosts] = await Promise.all([
    safe(() => campusApi.userProfile(uid), fallbackProfile),
    safe(() => campusApi.userPosts(uid), posts.value.filter((post) => post.author.uid === uid) as PostView[]),
    safe(() => campusApi.userLikes(uid), [] as PostView[]),
    safe(() => campusApi.userFavorites(uid), [] as PostView[])
  ]);
  viewedProfile.value = profile;
  viewedProfilePosts.value = enrichApiPosts(authorPosts);
  viewedProfileLikedPosts.value = enrichApiPosts(likedPosts);
  viewedProfileFavoritePosts.value = enrichApiPosts(favoritePosts);
  activeView.value = profile.mine ? 'profile' : 'user-profile';
  await nextTick();
  document.querySelector('.home-feed')?.scrollTo({ top: 0, behavior: 'smooth' });
}

function navigateTo(view: ViewKey) {
  if (view !== 'home' && !hasValidSession()) {
    openLoginDialog(protectedViewMessage(view));
    return;
  }
  if (view === 'profile') return openProfileView();
  activeView.value = view;
  if (view === 'messages') markMessageTabRead(activeMessageTab.value);
  if (view !== 'messages') closeConversation();
}

function protectedViewMessage(view: ViewKey) {
  if (view === 'schedule') return '请先登录后使用课程表';
  if (view === 'compose') return '请先登录后发布 PO';
  if (view === 'messages') return '请先登录后查看消息';
  if (view === 'profile' || view === 'user-profile') return '请先登录后查看个人主页';
  return '请先登录后继续操作';
}

function selectMessageTab(tab: MessageTabKey) {
  activeMessageTab.value = tab;
  markMessageTabRead(tab);
}

function syncProfileDraft() {
  clearAvatarPreview();
  clearAvatarCrop();
  profileAvatarUploadToken += 1;
  profileAvatarUploading.value = false;
  profileAvatarUploadError.value = '';
  profileNicknameError.value = '';
  profileNicknameChecking.value = false;
  profileNicknameAvailabilityToken += 1;
  profileAvatarUploadPromise = null;
  profileDraft.value = {
    id: currentUser.value.id,
    publicUid: currentUser.value.publicUid,
    nickname: currentUser.value.nickname,
    avatarUrl: currentUser.value.avatarUrl || fallbackAvatar(currentUser.value.nickname),
    bio: stripProfileMotto(currentUser.value.bio),
    motto: extractProfileMotto(currentUser.value.bio),
    levelTitle: currentUser.value.levelTitle,
    grade: currentUser.value.grade
  };
}

function selectBoard(board: BoardCode) {
  activeBoard.value = board;
  feedPage.value = 1;
}

function selectCalendarDate(dateKey: string) {
  activeView.value = 'home';
  selectedDate.value = selectedDate.value === dateKey ? null : dateKey;
  feedPage.value = 1;
  nextTick(() => {
    const feed = document.querySelector('.home-feed');
    feed?.scrollTo({ top: 0, behavior: 'smooth' });
  });
}

function goFeedPage(delta: number) {
  feedPage.value = Math.min(feedPageCount.value, Math.max(1, feedPage.value + delta));
  nextTick(() => {
    document.querySelector('.home-feed')?.scrollTo({ top: showSpotlight.value ? 360 : 0, behavior: 'smooth' });
  });
}

async function jumpToPost(post: CampusPost) {
  activeView.value = 'home';
  activeBoard.value = 'recommend';
  selectedDate.value = null;
  selectedPost.value = post;
  await nextTick();
  document.querySelector(`[data-post-id="${post.id}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
}

async function openPost(post: CampusPost) {
  const token = ++activePostOpenToken;
  selectedPost.value = post;
  expandedPost.value = post;
  showPostCommentBox.value = false;
  newCommentText.value = '';
  replyTargetId.value = null;
  replyText.value = '';
  await refreshPostComments(post.id);
  if (token === activePostOpenToken && expandedPost.value?.id === post.id) {
    expandedPost.value = posts.value.find((item) => item.id === post.id) || post;
  }
}

async function refreshPostComments(postId: number) {
  const apiComments = await safe(() => campusApi.comments(postId), null as CommentView[] | null);
  if (!apiComments) return;
  comments.value = [
    ...comments.value.filter((comment) => comment.postId !== postId),
    ...apiComments.map(normalizeComment)
  ];
}

async function refreshPostFromServer(postId: number) {
  const apiPosts = await safe(() => campusApi.posts(activeBoard.value), null as PostView[] | null);
  if (!apiPosts) return;
  const freshPosts = enrichApiPosts(apiPosts);
  posts.value = posts.value.map((post) => freshPosts.find((fresh) => fresh.id === post.id) || post);
  const freshExpanded = freshPosts.find((post) => post.id === postId);
  if (freshExpanded && expandedPost.value?.id === postId) {
    expandedPost.value = freshExpanded;
  }
}

function openProfileEditor() {
  if (!requireLogin('请先登录后编辑资料')) return;
  syncProfileDraft();
}

function closeProfileEditor() {
  clearAvatarPreview();
  clearAvatarCrop();
  profileAvatarUploadToken += 1;
  profileAvatarUploading.value = false;
  profileAvatarUploadError.value = '';
  profileAvatarUploadPromise = null;
  profileDraft.value = null;
}

function clearAvatarPreview() {
  if (profileAvatarPreviewUrl.value) {
    URL.revokeObjectURL(profileAvatarPreviewUrl.value);
    profileAvatarPreviewUrl.value = '';
  }
}

function clearAvatarCrop() {
  if (avatarCropImageUrl.value) {
    URL.revokeObjectURL(avatarCropImageUrl.value);
  }
  avatarCropOpen.value = false;
  avatarCropImageUrl.value = '';
  avatarCropSourceFile.value = null;
  avatarCropNaturalWidth.value = 0;
  avatarCropNaturalHeight.value = 0;
  avatarCropScale.value = 1;
  avatarCropMinScale.value = 1;
  avatarCropMaxScale.value = 4;
  avatarCropOffsetX.value = 0;
  avatarCropOffsetY.value = 0;
  avatarCropDragState = null;
}

function clearPostImagePreview() {
  if (postImagePreviewUrl.value) {
    URL.revokeObjectURL(postImagePreviewUrl.value);
    postImagePreviewUrl.value = '';
  }
}

function selectDraftBoard(board: ComposeDraft['board']) {
  draft.value.board = board;
  composeErrors.value.board = '';
}

function syncDraftContentFromEditor() {
  draft.value.content = postContentInput.value?.innerHTML || '';
  if (plainPostContent(draft.value.content).trim()) composeErrors.value.content = '';
  saveDraftSelection();
}

function focusDraftEditor() {
  const editor = postContentInput.value;
  editor?.focus();
  if (editor) {
    const selection = window.getSelection();
    selection?.removeAllRanges();
    if (draftSavedRange) {
      selection?.addRange(draftSavedRange);
    } else {
      const range = document.createRange();
      range.selectNodeContents(editor);
      range.collapse(false);
      selection?.addRange(range);
      draftSavedRange = range.cloneRange();
    }
  }
}

function saveDraftSelection() {
  const editor = postContentInput.value;
  const selection = window.getSelection();
  if (!editor || !selection || selection.rangeCount === 0) return;
  const range = selection.getRangeAt(0);
  if (editor.contains(range.commonAncestorContainer)) {
    draftSavedRange = range.cloneRange();
  }
}

function normalizeRichEditorMarkup(root: HTMLElement | DocumentFragment) {
  root.querySelectorAll('font').forEach((node) => {
    const span = document.createElement('span');
    const size = node.getAttribute('size');
    const color = node.getAttribute('color');
    if (size) {
      span.classList.add(Number(size) >= 4 ? 'rich-post-content__large' : 'rich-post-content__small');
    }
    if (color && /^(#[0-9a-fA-F]{3,8}|rgb\(\s*\d{1,3}\s*,\s*\d{1,3}\s*,\s*\d{1,3}\s*\))$/.test(color.trim())) {
      span.style.color = color.trim();
    }
    span.innerHTML = node.innerHTML;
    node.replaceWith(span);
  });
}

function insertDraftHtml(html: string) {
  focusDraftEditor();
  document.execCommand('insertHTML', false, html);
  syncDraftContentFromEditor();
}

function insertDraftText(text: string) {
  focusDraftEditor();
  document.execCommand('insertText', false, text);
  syncDraftContentFromEditor();
}

function formatDraftContent(command: 'bold' | 'insertUnorderedList') {
  focusDraftEditor();
  document.execCommand(command, false);
  syncDraftContentFromEditor();
}

function applyDraftColor(color: string) {
  focusDraftEditor();
  document.execCommand('foreColor', false, color);
  if (postContentInput.value) normalizeRichEditorMarkup(postContentInput.value);
  syncDraftContentFromEditor();
}

function applyDraftFont(size: 'large' | 'small') {
  focusDraftEditor();
  document.execCommand('fontSize', false, size === 'large' ? '5' : '2');
  if (postContentInput.value) normalizeRichEditorMarkup(postContentInput.value);
  syncDraftContentFromEditor();
}

function handleDraftPaste(event: ClipboardEvent) {
  event.preventDefault();
  insertDraftText(event.clipboardData?.getData('text/plain') || '');
}

function resetDraftEditor() {
  if (postContentInput.value) postContentInput.value.innerHTML = '';
}

function validateDraft() {
  syncDraftContentFromEditor();
  const errors = {
    board: draft.value.board ? '' : '请选择发布范围',
    title: draft.value.title.trim() ? '' : '请填写标题',
    content: plainPostContent(draft.value.content).trim() ? '' : '请填写正文内容'
  };
  composeErrors.value = errors;
  return !errors.board && !errors.title && !errors.content;
}

function setDraftSelection(start: number, end = start) {
  nextTick(() => {
    postContentInput.value?.focus();
    if (postContentInput.value instanceof HTMLTextAreaElement) {
      postContentInput.value.setSelectionRange(start, end);
    }
  });
}

function wrapDraftContent(prefix: string, suffix = prefix, placeholder = '内容') {
  const input = postContentInput.value instanceof HTMLTextAreaElement ? postContentInput.value : null;
  const start = input?.selectionStart ?? draft.value.content.length;
  const end = input?.selectionEnd ?? draft.value.content.length;
  const selected = draft.value.content.slice(start, end) || placeholder;
  const nextText = `${prefix}${selected}${suffix}`;
  draft.value.content = `${draft.value.content.slice(0, start)}${nextText}${draft.value.content.slice(end)}`;
  const selectionStart = start + prefix.length;
  setDraftSelection(selectionStart, selectionStart + selected.length);
}

async function handleAvatarFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file || !profileDraft.value) return;
  profileAvatarUploadToken += 1;
  profileAvatarUploadError.value = '';
  profileAvatarUploading.value = false;
  profileAvatarUploadPromise = null;
  clearAvatarPreview();
  await openAvatarCrop(file);
  input.value = '';
}

async function openAvatarCrop(file: File) {
  clearAvatarCrop();
  avatarCropSourceFile.value = file;
  avatarCropImageUrl.value = URL.createObjectURL(file);
  try {
    const dimensions = await readImageDimensions(avatarCropImageUrl.value);
    avatarCropNaturalWidth.value = dimensions.width;
    avatarCropNaturalHeight.value = dimensions.height;
    avatarCropOpen.value = true;
    await nextTick();
    resetAvatarCrop();
  } catch {
    profileAvatarUploadError.value = '图片读取失败，请换一张图片试试';
    clearAvatarCrop();
  }
}

function resetAvatarCrop() {
  const frameSize = avatarCropFrame.value?.clientWidth || 300;
  const cropSize = frameSize * 0.72;
  const minScale = Math.max(cropSize / avatarCropNaturalWidth.value, cropSize / avatarCropNaturalHeight.value);
  avatarCropMinScale.value = Number.isFinite(minScale) && minScale > 0 ? minScale : 1;
  avatarCropMaxScale.value = Math.max(4, avatarCropMinScale.value * 3);
  avatarCropScale.value = avatarCropMinScale.value;
  avatarCropOffsetX.value = 0;
  avatarCropOffsetY.value = 0;
  normalizeAvatarCrop();
}

function normalizeAvatarCrop() {
  if (!avatarCropNaturalWidth.value || !avatarCropNaturalHeight.value) return;
  if (avatarCropScale.value < avatarCropMinScale.value) avatarCropScale.value = avatarCropMinScale.value;
  if (avatarCropScale.value > avatarCropMaxScale.value) avatarCropScale.value = avatarCropMaxScale.value;
  const frameSize = avatarCropFrame.value?.clientWidth || 300;
  const cropSize = frameSize * 0.72;
  const displayWidth = avatarCropNaturalWidth.value * avatarCropScale.value;
  const displayHeight = avatarCropNaturalHeight.value * avatarCropScale.value;
  avatarCropOffsetX.value = clampNumber(avatarCropOffsetX.value, (cropSize - displayWidth) / 2, (displayWidth - cropSize) / 2);
  avatarCropOffsetY.value = clampNumber(avatarCropOffsetY.value, (cropSize - displayHeight) / 2, (displayHeight - cropSize) / 2);
}

function startAvatarCropDrag(event: PointerEvent) {
  if (!avatarCropOpen.value) return;
  avatarCropDragState = {
    pointerId: event.pointerId,
    startX: event.clientX,
    startY: event.clientY,
    originX: avatarCropOffsetX.value,
    originY: avatarCropOffsetY.value
  };
  avatarCropFrame.value?.setPointerCapture(event.pointerId);
}

function moveAvatarCropDrag(event: PointerEvent) {
  if (!avatarCropDragState || avatarCropDragState.pointerId !== event.pointerId) return;
  avatarCropOffsetX.value = avatarCropDragState.originX + event.clientX - avatarCropDragState.startX;
  avatarCropOffsetY.value = avatarCropDragState.originY + event.clientY - avatarCropDragState.startY;
  normalizeAvatarCrop();
}

function endAvatarCropDrag(event: PointerEvent) {
  if (!avatarCropDragState || avatarCropDragState.pointerId !== event.pointerId) return;
  avatarCropFrame.value?.releasePointerCapture(event.pointerId);
  avatarCropDragState = null;
}

function cancelAvatarCrop() {
  if (profileAvatarUploading.value) return;
  clearAvatarCrop();
}

async function confirmAvatarCrop() {
  if (!avatarCropSourceFile.value || !profileDraft.value) return;
  profileAvatarUploadError.value = '';
  profileAvatarUploading.value = true;
  const uploadToken = ++profileAvatarUploadToken;
  const draftId = profileDraft.value.id;
  try {
    const croppedFile = await createCroppedAvatarFile();
    profileAvatarPreviewUrl.value = URL.createObjectURL(croppedFile);
    profileAvatarUploadPromise = mediaApi.upload(croppedFile, 'avatar')
      .then((uploaded) => {
        if (uploadToken !== profileAvatarUploadToken || profileDraft.value?.id !== draftId) return null;
        profileDraft.value.avatarUrl = uploaded.url;
        clearAvatarPreview();
        clearAvatarCrop();
        return uploaded.url;
      })
      .catch((error) => {
        if (uploadToken === profileAvatarUploadToken) {
          const message = apiErrorMessage(error, '头像上传失败，请确认图片大小和对象存储配置');
          profileAvatarUploadError.value = message;
          showAuthNotice(message);
          clearAvatarPreview();
        }
        return null;
      })
      .finally(() => {
        if (uploadToken === profileAvatarUploadToken) {
          profileAvatarUploading.value = false;
        }
      });
    await profileAvatarUploadPromise;
  } catch {
    if (uploadToken === profileAvatarUploadToken) {
      profileAvatarUploading.value = false;
      profileAvatarUploadError.value = '头像裁剪失败，请换一张图片试试';
    }
  }
}

async function createCroppedAvatarFile() {
  if (!avatarCropImageUrl.value || !avatarCropFrame.value) throw new Error('missing crop source');
  const image = await loadImageElement(avatarCropImageUrl.value);
  const frameSize = avatarCropFrame.value.clientWidth || 300;
  const cropSize = frameSize * 0.72;
  const displayWidth = avatarCropNaturalWidth.value * avatarCropScale.value;
  const displayHeight = avatarCropNaturalHeight.value * avatarCropScale.value;
  const imageLeft = frameSize / 2 - displayWidth / 2 + avatarCropOffsetX.value;
  const imageTop = frameSize / 2 - displayHeight / 2 + avatarCropOffsetY.value;
  const cropLeft = (frameSize - cropSize) / 2;
  const cropTop = (frameSize - cropSize) / 2;
  const sourceX = (cropLeft - imageLeft) / avatarCropScale.value;
  const sourceY = (cropTop - imageTop) / avatarCropScale.value;
  const sourceSize = cropSize / avatarCropScale.value;
  const canvas = document.createElement('canvas');
  canvas.width = 512;
  canvas.height = 512;
  const context = canvas.getContext('2d');
  if (!context) throw new Error('missing canvas context');
  context.imageSmoothingQuality = 'high';
  context.drawImage(image, sourceX, sourceY, sourceSize, sourceSize, 0, 0, 512, 512);
  const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/png', 0.92));
  if (!blob) throw new Error('empty canvas blob');
  return new File([blob], avatarCropFilename(avatarCropSourceFile.value), { type: 'image/png' });
}

function readImageDimensions(url: string) {
  return loadImageElement(url).then((image) => ({ width: image.naturalWidth, height: image.naturalHeight }));
}

function loadImageElement(url: string) {
  return new Promise<HTMLImageElement>((resolve, reject) => {
    const image = new Image();
    image.onload = () => resolve(image);
    image.onerror = () => reject(new Error('image load failed'));
    image.src = url;
  });
}

function avatarCropFilename(file: File | null) {
  const stem = file?.name ? file.name.replace(/\.[^.]+$/, '') : 'avatar';
  return `${stem || 'avatar'}-square.png`;
}

async function checkProfileNickname() {
  if (!profileDraft.value) return false;
  const nickname = profileDraft.value.nickname.trim();
  profileNicknameError.value = '';
  if (!nickname) {
    profileNicknameError.value = '名称不能为空';
    return false;
  }
  if (nickname === currentUser.value.nickname) return true;
  const token = ++profileNicknameAvailabilityToken;
  profileNicknameChecking.value = true;
  try {
    const result = await authApi.nicknameAvailability(nickname);
    if (token !== profileNicknameAvailabilityToken) return false;
    if (!result.available) {
      profileNicknameError.value = '这个名称已经有人用了';
      showAuthNotice('这个名称已经有人用了，请换一个', 'error');
      return false;
    }
    return true;
  } catch (error) {
    showAuthNotice(apiErrorMessage(error, '名称校验失败，请稍后再试'), 'error');
    return false;
  } finally {
    if (token === profileNicknameAvailabilityToken) profileNicknameChecking.value = false;
  }
}

async function handlePostImageFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  if (!isAuthenticated.value) {
    input.value = '';
    openLoginDialog('请先登录后上传图片');
    return;
  }
  const previousCoverUrl = draft.value.coverUrl;
  clearPostImagePreview();
    postImagePreviewUrl.value = URL.createObjectURL(file);
    draft.value.coverUrl = postImagePreviewUrl.value;
  try {
    const uploaded = await mediaApi.upload(file, 'post');
    draft.value.coverUrl = uploaded.url;
    clearPostImagePreview();
  } catch (error) {
    draft.value.coverUrl = previousCoverUrl;
    clearPostImagePreview();
    showAuthNotice(apiErrorMessage(error, '图片上传失败，请确认图片大小和对象存储配置'));
  }
  input.value = '';
}

async function handleInlinePostImageFile(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  if (!isAuthenticated.value) {
    input.value = '';
    openLoginDialog('请先登录后插入图片');
    return;
  }
  try {
    const uploaded = await mediaApi.upload(file, 'post');
    focusDraftEditor();
    insertDraftHtml(`<div><img src="${uploaded.url}" alt="插入图片" /></div><div><br></div>`);
  } catch (error) {
    showAuthNotice(apiErrorMessage(error, '图片上传失败，请确认图片大小和对象存储配置'));
  }
  input.value = '';
}

async function saveProfile() {
  if (!isAuthenticated.value) {
    closeProfileEditor();
    openLoginDialog('请先登录后编辑资料');
    return;
  }
  if (!profileDraft.value) return;
  if (profileAvatarUploading.value && profileAvatarUploadPromise) {
    const uploadedUrl = await profileAvatarUploadPromise;
    if (!uploadedUrl || !profileDraft.value) return;
  }
  const nicknameAvailable = await checkProfileNickname();
  if (!nicknameAvailable || !profileDraft.value) return;
  let updated: UserProfile | null = null;
  try {
    updated = await userApi.updateProfile({
      nickname: profileDraft.value.nickname.trim(),
      avatarUrl: profileDraft.value.avatarUrl?.trim(),
      bio: composeProfileBio(profileDraft.value.bio, profileDraft.value.motto)
    });
  } catch (error) {
    if (isAuthError(error)) {
      await handleAuthSensitiveError(error, '登录状态失效，请重新登录后再试', '资料保存失败，请稍后再试');
      return;
    }
    const message = apiErrorMessage(error, '资料保存失败，请稍后再试');
    profileNicknameError.value = message.includes('名称') || message.includes('用户名') ? message : profileNicknameError.value;
    showAuthNotice(message, 'error');
    return;
  }
  if (updated) {
    currentUser.value = updated;
    const updatedAuthor = authorFromUser(updated);
    posts.value = posts.value.map((post) => post.author.id === updated.id ? { ...post, author: updatedAuthor } : post);
    profilePosts.value = profilePosts.value.map((post) => post.author.id === updated.id ? { ...post, author: updatedAuthor } : post);
    profileLikedPosts.value = profileLikedPosts.value.map((post) => post.author.id === updated.id ? { ...post, author: updatedAuthor } : post);
    profileFavoritePosts.value = profileFavoritePosts.value.map((post) => post.author.id === updated.id ? { ...post, author: updatedAuthor } : post);
    comments.value = comments.value.map((comment) => comment.author.id === updated.id ? { ...comment, author: updatedAuthor } : comment);
    if (expandedPost.value?.author.id === updated.id) {
      expandedPost.value = { ...expandedPost.value, author: updatedAuthor };
    }
  } else if (profileDraft.value) {
    currentUser.value = {
      ...currentUser.value,
      nickname: profileDraft.value.nickname,
      avatarUrl: profileDraft.value.avatarUrl,
      bio: composeProfileBio(profileDraft.value.bio, profileDraft.value.motto)
    };
  }
  closeProfileEditor();
}

async function publishLocalPost() {
  if (!requireLogin('请先登录后发布 PO')) return;
  if (!validateDraft()) return;
  const contentHtml = sanitizeRichHtml(draft.value.content.trim());
  const coverUrl = draft.value.coverUrl.trim();
  const payload = {
    board: draft.value.board,
    title: draft.value.title.trim(),
    content: contentHtml,
    coverUrl: coverUrl || undefined,
    images: draft.value.images,
    tags: ['新PO', boardName(draft.value.board)]
  };
  const apiPost = await safe(() => postApi.create(payload), null as PostView | null);
  if (!apiPost) return openLoginDialog('登录状态失效，PO 没有写入后端');
  const post: CampusPost = enrichApiPosts([apiPost])[0];
  posts.value = [post, ...posts.value];
  profilePosts.value = [post, ...profilePosts.value];
  userStats.value.posts += 1;
  selectedPost.value = post;
  activeView.value = 'home';
  clearPostImagePreview();
  draft.value = { board: 'school', title: '', content: '', coverUrl: '', images: [] };
  composeErrors.value = { board: '', title: '', content: '' };
  resetDraftEditor();
}

async function togglePostLike(post: CampusPost) {
  if (!requireLogin()) return;
  const liked = post.liked;
  const nextLiked = !liked;
  const ok = await safe(() => nextLiked ? campusApi.like(post.id) : campusApi.unlike(post.id), false);
  if (!ok) return openLoginDialog('登录状态失效，请重新登录后再试');
  post.liked = nextLiked;
  post.likeCount = Math.max(0, post.likeCount + (nextLiked ? 1 : -1));
  userStats.value.likedPosts = Math.max(0, userStats.value.likedPosts + (nextLiked ? 1 : -1));
  profileLikedPosts.value = nextLiked
    ? [post, ...profileLikedPosts.value.filter((item) => item.id !== post.id)]
    : profileLikedPosts.value.filter((item) => item.id !== post.id);
  await refreshPostFromServer(post.id);
  await refreshInteractionNotices();
}

async function togglePostFavorite(post: CampusPost) {
  if (!requireLogin()) return;
  const favorited = post.favorited;
  const nextFavorited = !favorited;
  const ok = await safe(() => nextFavorited ? campusApi.favorite(post.id) : campusApi.unfavorite(post.id), false);
  if (!ok) return openLoginDialog('登录状态失效，请重新登录后再试');
  post.favorited = nextFavorited;
  post.favoriteCount = Math.max(0, post.favoriteCount + (nextFavorited ? 1 : -1));
  userStats.value.favorites = Math.max(0, userStats.value.favorites + (nextFavorited ? 1 : -1));
  profileFavoritePosts.value = nextFavorited
    ? [post, ...profileFavoritePosts.value.filter((item) => item.id !== post.id)]
    : profileFavoritePosts.value.filter((item) => item.id !== post.id);
  await refreshPostFromServer(post.id);
  await refreshInteractionNotices();
}

function canDeletePost(post: CampusPost) {
  return profileTab.value === 'posts' && hasValidSession() && post.author.id === currentUser.value.id;
}

function isDeletingPost(postId: number) {
  return deletingPostIds.value.has(postId);
}

function setPostDeleting(postId: number, deleting: boolean) {
  const next = new Set(deletingPostIds.value);
  if (deleting) {
    next.add(postId);
  } else {
    next.delete(postId);
  }
  deletingPostIds.value = next;
}

async function deleteMyPost(post: CampusPost) {
  if (!requireLogin('请先登录后删除自己的 PO')) return;
  if (!canDeletePost(post) || isDeletingPost(post.id)) return;
  const confirmed = window.confirm('确定删除这条 PO 吗？删除后不会在首页和我的作品里展示。');
  if (!confirmed) return;
  setPostDeleting(post.id, true);
  const ok = await safe(() => postApi.remove(post.id), false);
  setPostDeleting(post.id, false);
  if (!ok) {
    showAuthNotice('删除失败，请确认这是你自己的 PO 后再试', 'error');
    return;
  }
  removePostFromLocalState(post.id);
  userStats.value.posts = Math.max(0, userStats.value.posts - 1);
  showAuthNotice('已删除这条 PO');
  await refreshInteractionNotices();
}

function removePostFromLocalState(postId: number) {
  posts.value = posts.value.filter((item) => item.id !== postId);
  profilePosts.value = profilePosts.value.filter((item) => item.id !== postId);
  profileLikedPosts.value = profileLikedPosts.value.filter((item) => item.id !== postId);
  profileFavoritePosts.value = profileFavoritePosts.value.filter((item) => item.id !== postId);
  viewedProfilePosts.value = viewedProfilePosts.value.filter((item) => item.id !== postId);
  viewedProfileLikedPosts.value = viewedProfileLikedPosts.value.filter((item) => item.id !== postId);
  viewedProfileFavoritePosts.value = viewedProfileFavoritePosts.value.filter((item) => item.id !== postId);
  comments.value = comments.value.filter((comment) => comment.postId !== postId);
  interactionNotices.value = interactionNotices.value.filter((notice) => notice.post?.id !== postId);
  if (selectedPost.value?.id === postId) {
    selectedPost.value = posts.value[0] || null;
  }
  if (expandedPost.value?.id === postId) {
    expandedPost.value = null;
  }
}

function togglePostCommentBox() {
  if (!requireLogin('请先登录后评论 PO')) return;
  showPostCommentBox.value = !showPostCommentBox.value;
}

async function submitPostComment() {
  if (!expandedPost.value) return;
  if (!requireLogin()) return;
  const content = newCommentText.value.trim().slice(0, 200);
  if (!content) return;
  const created = await safe(() => campusApi.comment(expandedPost.value!.id, { content }), null as CommentView | null);
  if (!created) return openLoginDialog('登录状态失效，评论没有写入后端');
  comments.value = [normalizeComment(created), ...comments.value];
  expandedPost.value.commentCount += 1;
  userStats.value.comments += 1;
  await refreshPostFromServer(expandedPost.value.id);
  await refreshInteractionNotices();
  newCommentText.value = '';
  showPostCommentBox.value = false;
}

function startReply(comment: CampusComment) {
  if (!requireLogin('请先登录后回复评论')) return;
  replyTargetId.value = comment.id;
  replyText.value = '';
}

async function submitReply(parent: CampusComment) {
  if (!expandedPost.value) return;
  if (!requireLogin()) return;
  const content = replyText.value.trim().slice(0, 200);
  if (!content) return;
  const created = await safe(() => campusApi.comment(expandedPost.value!.id, { content, parentId: parent.id }), null as CommentView | null);
  if (!created) return openLoginDialog('登录状态失效，回复没有写入后端');
  comments.value = [...comments.value, normalizeComment(created)];
  expandedPost.value.commentCount += 1;
  parent.replyCount += 1;
  await refreshPostFromServer(expandedPost.value.id);
  await refreshInteractionNotices();
  expandedReplyCounts.value[parent.id] = Math.max(expandedReplyCounts.value[parent.id] || 0, Math.min(5, repliesFor(parent).length + 1));
  replyTargetId.value = null;
  replyText.value = '';
}

function createComment(postId: number, content: string, parentId?: number): CampusComment {
  return {
    id: Date.now() + Math.floor(Math.random() * 1000),
    postId,
    userId: currentUser.value.id,
    parentId,
    content,
    likeCount: 0,
    replyCount: 0,
    liked: false,
    featured: false,
    status: 'visible',
    createdAt: new Date().toISOString(),
    author: authorFromUser(currentUser.value)
  };
}

function normalizeComment(comment: CommentView): CampusComment {
  return {
    ...comment,
    liked: Boolean(comment.liked),
    author: comment.author as CampusComment['author']
  };
}

async function toggleCommentLike(comment: CampusComment) {
  if (!requireLogin()) return;
  const liked = Boolean(comment.liked);
  const nextLiked = !liked;
  const ok = await safe(() => nextLiked ? campusApi.likeComment(comment.id) : campusApi.unlikeComment(comment.id), false);
  if (!ok) return openLoginDialog('登录状态失效，请重新登录后再试');
  comment.liked = nextLiked;
  comment.likeCount = Math.max(0, comment.likeCount + (nextLiked ? 1 : -1));
  comments.value = comments.value.map((item) => item.id === comment.id ? { ...item, liked: nextLiked, likeCount: comment.likeCount } : item);
}

function repliesFor(comment: CampusComment) {
  return detailComments.value.filter((item) => item.parentId === comment.id);
}

function expandedReplyCount(comment: CampusComment) {
  return expandedReplyCounts.value[comment.id] || 0;
}

function visibleReplies(comment: CampusComment) {
  return repliesFor(comment).slice(0, expandedReplyCount(comment));
}

function remainingReplyCount(comment: CampusComment) {
  return Math.max(0, repliesFor(comment).length - expandedReplyCount(comment));
}

function expandReplies(comment: CampusComment) {
  const current = expandedReplyCount(comment);
  const total = repliesFor(comment).length;
  expandedReplyCounts.value[comment.id] = Math.min(total, current + 5 || 5);
}

function hasValidSession() {
  return isAuthenticated.value && Boolean(localStorage.getItem('bcg_token'));
}

function requireLogin(message = '请先登录，点赞、评论和收藏才会写入后端') {
  if (hasValidSession()) return true;
  openLoginDialog(message);
  return false;
}

function normalizeMessage(message: MessageView): CampusMessage {
  return {
    ...message,
    read: Boolean(message.read)
  };
}

function messageIsMine(message: MessageView) {
  return message.senderId === currentUser.value.id;
}

function messageAvatar(message: MessageView) {
  if (messageIsMine(message)) return currentUser.value.avatarUrl || fallbackAvatar(currentUser.value.nickname);
  return activeConversation.value?.peer.avatarUrl || fallbackAvatar(activeConversation.value?.peer.nickname || 'PO');
}

function isLastOutgoingMessage(message: CampusMessage) {
  const outgoing = activeConversationMessages.value.filter(messageIsMine);
  return outgoing[outgoing.length - 1]?.id === message.id;
}

function appendConversationMessage(message: CampusMessage) {
  const existing = conversationMessages.value[message.conversationId] || [];
  conversationMessages.value = {
    ...conversationMessages.value,
    [message.conversationId]: [...existing, message]
  };
}

function replaceOptimisticMessage(conversationId: number, optimisticId: number, message: CampusMessage) {
  const existing = conversationMessages.value[conversationId] || [];
  conversationMessages.value = {
    ...conversationMessages.value,
    [conversationId]: existing.map((item) => item.id === optimisticId ? message : item)
  };
}

function removeOptimisticMessage(conversationId: number, optimisticId: number) {
  const existing = conversationMessages.value[conversationId] || [];
  conversationMessages.value = {
    ...conversationMessages.value,
    [conversationId]: existing.filter((item) => item.id !== optimisticId)
  };
}

function mergeConversationMessages(conversationId: number, incoming: CampusMessage[]) {
  const existing = conversationMessages.value[conversationId] || [];
  const pendingOptimistic = existing.filter((message) => message.optimistic);
  const merged = new Map<number, CampusMessage>();
  existing.filter((message) => !message.optimistic).forEach((message) => merged.set(message.id, message));
  incoming.forEach((message) => merged.set(message.id, message));
  conversationMessages.value = {
    ...conversationMessages.value,
    [conversationId]: [
      ...Array.from(merged.values()),
      ...pendingOptimistic
    ].sort((a, b) => dayjs(a.createdAt).valueOf() - dayjs(b.createdAt).valueOf())
  };
}

function updateConversationPreview(conversationId: number, lastMessage: string, createdAt = new Date().toISOString()) {
  conversations.value = conversations.value
    .map((conversation) => conversation.id === conversationId
      ? {
          ...conversation,
          lastMessage,
          unreadCount: 0,
          lastMessageAt: createdAt,
          updatedAt: createdAt
        }
      : conversation
    )
    .sort((a, b) => dayjs(b.lastMessageAt || b.updatedAt).valueOf() - dayjs(a.lastMessageAt || a.updatedAt).valueOf());
}

function markConversationRead(conversationId: number) {
  const conversation = conversations.value.find((item) => item.id === conversationId);
  if (!conversation?.unreadCount) return;
  unreadCount.value = Math.max(0, unreadCount.value - conversation.unreadCount);
  conversations.value = conversations.value.map((item) => item.id === conversationId ? { ...item, unreadCount: 0 } : item);
}

async function refreshActiveConversation(options: { scroll?: boolean } = {}) {
  const conversationId = activeConversationId.value;
  if (conversationId == null || !isAuthenticated.value || !localStorage.getItem('bcg_token') || refreshingActiveConversation) return;
  refreshingActiveConversation = true;
  const currentLength = conversationMessages.value[conversationId]?.length || 0;
  try {
    const loadedMessages = await campusApi.messages(conversationId);
    mergeConversationMessages(conversationId, loadedMessages.map(normalizeMessage));
    markConversationRead(conversationId);
    await refreshConversations();
    const nextLength = conversationMessages.value[conversationId]?.length || 0;
    if (options.scroll || nextLength > currentLength) {
      await nextTick();
      scrollMessageThreadToBottom();
    }
  } catch {
    // Polling should stay quiet; manual reopen still retries through openConversation.
  } finally {
    refreshingActiveConversation = false;
  }
}

function startActiveConversationPolling() {
  stopActiveConversationPolling();
  activeConversationTimer = window.setInterval(() => {
    refreshActiveConversation();
  }, 1800);
}

function stopActiveConversationPolling() {
  if (activeConversationTimer) {
    window.clearInterval(activeConversationTimer);
    activeConversationTimer = undefined;
  }
}

function scrollMessageThreadToBottom() {
  const body = document.querySelector('.message-thread-body');
  body?.scrollTo({ top: body.scrollHeight, behavior: 'smooth' });
}

function isFollowNoticeText(text?: string) {
  return Boolean(text?.trim().endsWith('关注了你'));
}

function isFollowOnlyConversation(conversation: ConversationView) {
  return isFollowNoticeText(conversation.lastMessage);
}

function authorTitle(author: AuthorView) {
  if (author.role === 'SCHOOL_OPERATOR') return '校霸情报台';
  if (author.role === 'COLLEGE_OPERATOR') return '院花情报台';
  if (author.role === 'MAJOR_OPERATOR') return '级草情报台';
  if (author.role === 'ADMIN') return '管理员';
  return `Lv.${author.level} ${levelTitleFor(author.level)}`;
}

function isUserOnline(author: AuthorView) {
  if (isAuthenticated.value && author.id === currentUser.value.id) return true;
  return Boolean(author.online);
}

function presenceLabel(author: AuthorView) {
  return isUserOnline(author) ? '在线' : '离线';
}

function presenceClass(author: AuthorView) {
  return isUserOnline(author) ? 'presence-pill--online' : 'presence-pill--offline';
}

async function toggleViewedProfileFollow() {
  if (!viewedProfile.value || viewedProfile.value.mine) return;
  if (!requireLogin()) return;
  const uid = viewedProfile.value.profile.publicUid;
  const following = viewedProfile.value.following;
  const nextFollowing = !following;
  try {
    await (nextFollowing ? campusApi.follow(uid) : campusApi.unfollow(uid));
  } catch (error) {
    await handleAuthSensitiveError(error, '登录状态失效，请重新登录后再试', nextFollowing ? '关注失败，请稍后再试' : '取消关注失败，请稍后再试');
    return;
  }
  viewedProfile.value = {
    ...viewedProfile.value,
    following: nextFollowing,
    stats: {
      ...viewedProfile.value.stats,
      followers: Math.max(0, viewedProfile.value.stats.followers + (nextFollowing ? 1 : -1))
    }
  };
  userStats.value.following = Math.max(0, userStats.value.following + (nextFollowing ? 1 : -1));
  await refreshConversations();
}

async function openMessageDraft(profile: UserProfile) {
  if (!requireLogin()) return;
  let conversation: ConversationView | null = null;
  try {
    conversation = await campusApi.createConversation(profile.publicUid);
  } catch (error) {
    await handleAuthSensitiveError(error, '登录状态失效，请重新登录后再试', '私信会话创建失败，请稍后再试');
    return;
  }
  if (!conversation) {
    showAuthNotice('私信会话创建失败，请稍后再试', 'error');
    return;
  }
  conversations.value = [
    conversation,
    ...conversations.value.filter((item) => item.id !== conversation.id)
  ];
  activeView.value = 'messages';
  await openConversation(conversation);
}

async function openConversation(conversation: ConversationView) {
  if (!requireLogin()) return;
  activeConversationId.value = conversation.id;
  messageInput.value = '';
  startActiveConversationPolling();
  await refreshActiveConversation({ scroll: true });
}

function closeConversation() {
  stopActiveConversationPolling();
  activeConversationId.value = null;
  messageInput.value = '';
  refreshConversations();
}

async function sendConversationMessage() {
  if (!activeConversation.value) return;
  if (!requireLogin()) return;
  const content = messageInput.value.trim().slice(0, 300);
  if (!content) return;
  const conversationId = activeConversation.value.id;
  const optimistic: CampusMessage = {
    id: Date.now(),
    conversationId,
    senderId: currentUser.value.id,
    receiverId: activeConversation.value.peer.id,
    content,
    read: false,
    createdAt: new Date().toISOString(),
    optimistic: true
  };
  appendConversationMessage(optimistic);
  messageInput.value = '';
  updateConversationPreview(conversationId, content);
  await nextTick();
  scrollMessageThreadToBottom();
  let sent: MessageView | null = null;
  try {
    sent = await campusApi.sendMessage(conversationId, content);
  } catch (error) {
    removeOptimisticMessage(conversationId, optimistic.id);
    await handleAuthSensitiveError(error, '登录状态失效，私信没有发送成功', '私信发送失败，请稍后再试');
    await refreshActiveConversation({ scroll: true });
    return;
  }
  if (!sent) {
    showAuthNotice('私信发送失败，请稍后再试', 'error');
    return;
  }
  replaceOptimisticMessage(conversationId, optimistic.id, normalizeMessage(sent));
  updateConversationPreview(conversationId, content, sent.createdAt);
  await refreshActiveConversation({ scroll: true });
  await nextTick();
  scrollMessageThreadToBottom();
}

function openLoginDialog(message?: string) {
  if (message) showAuthNotice(message);
  localStorage.removeItem('bcg_token');
  isAuthenticated.value = false;
  stopPresenceHeartbeat();
  currentUser.value = fallbackUser();
  userStats.value = fallbackStats();
  profilePosts.value = [];
  profileLikedPosts.value = [];
  profileFavoritePosts.value = [];
  interactionNotices.value = [];
  conversations.value = [];
  conversationMessages.value = {};
  readMessageNoticeIds.value = new Set();
  unreadCount.value = 0;
  closeTransientOverlays();
  closeConversation();
  if (activeView.value !== 'home') {
    activeView.value = 'home';
  }
  resetAuthForms();
  clearAuthErrors();
  authSubmitting.value = false;
  verificationCodeSending.value = false;
  authMode.value = 'login';
  showLoginDialog.value = true;
}

function closeAuthDialog() {
  showLoginDialog.value = false;
  authSubmitting.value = false;
  verificationCodeSending.value = false;
  clearAuthErrors();
}

function closeTransientOverlays() {
  accountDrawerOpen.value = false;
  showLevelCatalog.value = false;
  if (profileDraft.value) closeProfileEditor();
  editDraft.value = null;
  expandedPost.value = null;
  clearAvatarPreview();
  clearAvatarCrop();
}

function openAccountCenter() {
  accountDrawerOpen.value = false;
  showAuthNotice('账号中心入口已预留，后续接入账号设置页');
}

function openDrawerLogin() {
  accountDrawerOpen.value = false;
  openLoginDialog();
}

async function logoutCurrentUser() {
  stopPresenceHeartbeat();
  await safe(() => authApi.logout(), false);
  localStorage.removeItem('bcg_token');
  isAuthenticated.value = false;
  showLoginDialog.value = false;
  closeTransientOverlays();
  closeConversation();
  viewedProfile.value = null;
  viewedProfilePosts.value = [];
  viewedProfileLikedPosts.value = [];
  viewedProfileFavoritePosts.value = [];
  conversations.value = [];
  conversationMessages.value = {};
  readMessageNoticeIds.value = new Set();
  resetCheckInState();
  unreadCount.value = 0;
  currentUser.value = fallbackUser();
  userStats.value = fallbackStats();
  profilePosts.value = [];
  profileLikedPosts.value = [];
  profileFavoritePosts.value = [];
  activeView.value = 'home';
  showAuthNotice('已退出登录');
}

function resetAuthForms() {
  authForms.value = {
    login: { email: '', password: '' },
    register: { nickname: '', email: '', verificationCode: '', password: '', confirmPassword: '' },
    reset: { email: '', verificationCode: '', password: '', confirmPassword: '' }
  };
}

async function loginWithDraft() {
  if (authSubmitting.value) return;
  clearAuthErrors();
  const email = authForms.value.login.email.trim();
  const password = authForms.value.login.password;
  let hasError = false;
  if (!email) {
    authErrors.value.login.email = '请输入邮箱或账号';
    hasError = true;
  }
  if (!password) {
    authErrors.value.login.password = '请输入密码';
    hasError = true;
  }
  if (hasError) return;
  authSubmitting.value = true;
  let result: { token: string; user: UserProfile } | null = null;
  try {
    result = await authApi.login(email, password);
  } catch (error) {
    const message = error instanceof Error ? error.message : '登录失败';
    authSubmitting.value = false;
    return showAuthNotice(`登录失败：${message}`, 'error');
  }
  localStorage.setItem('bcg_token', result.token);
  isAuthenticated.value = true;
  currentUser.value = result.user;
  startPresenceHeartbeat();
  authSubmitting.value = false;
  closeAuthDialog();
  accountDrawerOpen.value = false;
  authNotice.value = '';
  await loadCampus();
}

async function sendVerificationCode(purpose: 'register' | 'reset') {
  if (verificationCodeSending.value) return;
  if ((purpose === 'register' ? registerCodeCountdown.value : resetCodeCountdown.value) > 0) return;
  clearAuthFieldErrors(purpose, ['email', 'verificationCode']);
  const form = authForms.value[purpose];
  const email = form.email.trim();
  if (!email) {
    authErrors.value[purpose].email = '请输入邮箱';
    return;
  }
  verificationCodeSending.value = true;
  try {
    const apiPurpose = purpose === 'reset' ? 'reset-password' : 'register';
    const availability = await authApi.emailAvailability(email, apiPurpose);
    if (purpose === 'register' && !availability.available) {
      authErrors.value.register.email = '该邮箱已注册';
      showAuthNotice('该邮箱已注册，请直接登录或找回密码', 'error');
      return;
    }
    if (purpose === 'reset' && !availability.available) {
      authErrors.value.reset.email = '该邮箱尚未注册';
      showAuthNotice('该邮箱尚未注册，请先注册账号', 'error');
      return;
    }
    await authApi.requestVerificationCode({ email, purpose: apiPurpose });
    form.verificationCode = '';
    startVerificationCountdown(purpose);
  } catch (error) {
    const message = apiErrorMessage(error, '验证码发送失败');
    showAuthNotice(`验证码发送失败：${message}`, 'error');
  } finally {
    verificationCodeSending.value = false;
  }
}

async function checkRegisterNickname() {
  const nickname = authForms.value.register.nickname.trim();
  clearAuthFieldErrors('register', ['nickname']);
  if (!nickname) return false;
  const token = ++nicknameAvailabilityToken;
  try {
    const result = await authApi.nicknameAvailability(nickname);
    if (token !== nicknameAvailabilityToken) return false;
    if (!result.available) {
      authErrors.value.register.nickname = '该用户名已被占用';
      showAuthNotice('该用户名已被占用，请换一个', 'error');
      return false;
    }
    return true;
  } catch {
    return true;
  }
}

async function registerWithDraft() {
  if (authSubmitting.value) return;
  clearAuthErrors();
  const payload = {
    email: authForms.value.register.email.trim(),
    nickname: authForms.value.register.nickname.trim(),
    verificationCode: authForms.value.register.verificationCode.trim(),
    password: authForms.value.register.password,
    confirmPassword: authForms.value.register.confirmPassword
  };
  const missing = validateRegisterForm(payload);
  if (missing) return;
  const nicknameAvailable = await checkRegisterNickname();
  if (!nicknameAvailable) return;
  authSubmitting.value = true;
  try {
    const result = await authApi.register(payload);
    localStorage.setItem('bcg_token', result.token);
    isAuthenticated.value = true;
    currentUser.value = result.user;
    startPresenceHeartbeat();
    authSubmitting.value = false;
    closeAuthDialog();
    accountDrawerOpen.value = false;
    authNotice.value = '';
    await loadCampus();
  } catch (error) {
    authSubmitting.value = false;
    const message = apiErrorMessage(error, '注册失败');
    showAuthNotice(`注册失败：${message}`, 'error');
  }
}

async function resetPasswordWithDraft() {
  if (authSubmitting.value) return;
  clearAuthErrors();
  const payload = {
    email: authForms.value.reset.email.trim(),
    verificationCode: authForms.value.reset.verificationCode.trim(),
    password: authForms.value.reset.password,
    confirmPassword: authForms.value.reset.confirmPassword
  };
  const missing = validateResetForm(payload);
  if (missing) return;
  authSubmitting.value = true;
  try {
    await authApi.resetPassword(payload);
    authSubmitting.value = false;
    showAuthNotice('密码已重置，请返回登录');
    switchAuthMode('login');
    authForms.value.login.email = payload.email;
    authForms.value.login.password = '';
  } catch (error) {
    authSubmitting.value = false;
    const message = apiErrorMessage(error, '重置密码失败');
    showAuthNotice(`重置密码失败：${message}`, 'error');
  }
}

function switchAuthMode(mode: 'login' | 'register' | 'reset') {
  authMode.value = mode;
  clearAuthErrors();
}

function startVerificationCountdown(purpose: 'register' | 'reset') {
  stopVerificationCountdown(purpose);
  const countdown = purpose === 'register' ? registerCodeCountdown : resetCodeCountdown;
  countdown.value = 60;
  const timer = window.setInterval(() => {
    countdown.value = Math.max(0, countdown.value - 1);
    if (countdown.value <= 0) {
      stopVerificationCountdown(purpose);
    }
  }, 1000);
  if (purpose === 'register') {
    registerCodeCountdownTimer = timer;
  } else {
    resetCodeCountdownTimer = timer;
  }
}

function stopVerificationCountdown(purpose: 'register' | 'reset') {
  if (purpose === 'register') {
    if (registerCodeCountdownTimer) window.clearInterval(registerCodeCountdownTimer);
    registerCodeCountdownTimer = undefined;
    registerCodeCountdown.value = 0;
  } else {
    if (resetCodeCountdownTimer) window.clearInterval(resetCodeCountdownTimer);
    resetCodeCountdownTimer = undefined;
    resetCodeCountdown.value = 0;
  }
}

function clearAuthErrors() {
  authErrors.value = {
    login: { email: '', password: '' },
    register: { nickname: '', email: '', verificationCode: '', password: '', confirmPassword: '' },
    reset: { email: '', verificationCode: '', password: '', confirmPassword: '' }
  };
}

type AuthModeKey = 'login' | 'register' | 'reset';
type AuthFieldKey = 'email' | 'password' | 'nickname' | 'verificationCode' | 'confirmPassword';

function clearAuthFieldErrors(mode: AuthModeKey, fields: AuthFieldKey[]) {
  const bucket = authErrors.value[mode] as Record<AuthFieldKey, string>;
  for (const field of fields) {
    bucket[field] = '';
  }
}

function validateRegisterForm(payload: { email: string; nickname: string; verificationCode: string; password: string; confirmPassword: string }) {
  let hasError = false;
  if (!payload.nickname) {
    authErrors.value.register.nickname = '请输入用户名';
    hasError = true;
  }
  if (!payload.email) {
    authErrors.value.register.email = '请输入邮箱';
    hasError = true;
  }
  if (!payload.verificationCode) {
    authErrors.value.register.verificationCode = '请输入验证码';
    hasError = true;
  } else if (!/^\d{4}$/.test(payload.verificationCode)) {
    authErrors.value.register.verificationCode = '验证码必须是 4 位数字';
    hasError = true;
  }
  if (!payload.password) {
    authErrors.value.register.password = '请输入密码';
    hasError = true;
  }
  if (!payload.confirmPassword) {
    authErrors.value.register.confirmPassword = '请再次输入密码';
    hasError = true;
  } else if (payload.password !== payload.confirmPassword) {
    authErrors.value.register.confirmPassword = '两次密码不一致';
    hasError = true;
  }
  return hasError;
}

function validateResetForm(payload: { email: string; verificationCode: string; password: string; confirmPassword: string }) {
  let hasError = false;
  if (!payload.email) {
    authErrors.value.reset.email = '请输入邮箱';
    hasError = true;
  }
  if (!payload.verificationCode) {
    authErrors.value.reset.verificationCode = '请输入验证码';
    hasError = true;
  } else if (!/^\d{4}$/.test(payload.verificationCode)) {
    authErrors.value.reset.verificationCode = '验证码必须是 4 位数字';
    hasError = true;
  }
  if (!payload.password) {
    authErrors.value.reset.password = '请输入新密码';
    hasError = true;
  }
  if (!payload.confirmPassword) {
    authErrors.value.reset.confirmPassword = '请再次输入新密码';
    hasError = true;
  } else if (payload.password !== payload.confirmPassword) {
    authErrors.value.reset.confirmPassword = '两次密码不一致';
    hasError = true;
  }
  return hasError;
}

function showAuthNotice(message: string, tone: 'neutral' | 'error' = 'neutral') {
  authNotice.value = message;
  authNoticeTone.value = tone;
  window.setTimeout(() => {
    if (authNotice.value === message) authNotice.value = '';
  }, 2400);
}

function applyCheckInView(checkIn: CheckInView) {
  currentUser.value = checkIn.profile;
  checkedInToday.value = checkIn.checkedInToday;
  checkinStreak.value = checkIn.streak;
  lastCheckinXp.value = checkIn.xpGained;
}

function resetCheckInState() {
  checkedInToday.value = false;
  checkinStreak.value = 0;
  checkingIn.value = false;
  checkinCelebrating.value = false;
  lastCheckinXp.value = 0;
}

async function doCheckIn() {
  if (!isAuthenticated.value || !localStorage.getItem('bcg_token')) {
    openLoginDialog('请先登录后签到，经验会写入后端账号');
    return;
  }
  if (checkedInToday.value) return;
  checkingIn.value = true;
  const result = await safe(() => campusApi.checkIn(), null as CheckInView | null);
  checkingIn.value = false;
  if (!result) return openLoginDialog('登录状态失效，签到没有写入后端');
  applyCheckInView(result);
  if (!result.xpGained) return;
  checkinCelebrating.value = true;
  window.setTimeout(() => {
    checkinCelebrating.value = false;
  }, 1150);
}

function mockImportSchedule() {
  if (!requireLogin('请先登录后使用课程表')) return;
  importNotice.value = true;
  window.setTimeout(() => {
    importNotice.value = false;
  }, 3200);
}

function editCourse(course: Course) {
  if (!requireLogin('请先登录后编辑课程表')) return;
  editDraft.value = { ...course };
}

function saveCourse() {
  if (!requireLogin('请先登录后保存课程表')) return;
  if (!editDraft.value) return;
  const sourceCourse = courses.value.find((course) => course.id === editDraft.value?.id);
  const draftCourse = normalizeCourseWeeks(editDraft.value);
  const nextCourses = courses.value.filter((course) => course.id !== draftCourse.id);

  if (sourceCourse) {
    if (sourceCourse.startWeek < draftCourse.startWeek) {
      nextCourses.push({
        ...sourceCourse,
        id: nextCourseId(),
        endWeek: draftCourse.startWeek - 1
      });
    }
    if (sourceCourse.endWeek > draftCourse.endWeek) {
      nextCourses.push({
        ...sourceCourse,
        id: nextCourseId(),
        startWeek: draftCourse.endWeek + 1
      });
    }
  }

  nextCourses.push(draftCourse);
  courses.value = nextCourses.sort((a, b) => a.seriesId - b.seriesId || a.startWeek - b.startWeek || a.day - b.day || a.start.localeCompare(b.start));
  editDraft.value = null;
}

function coursesForDay(day: number) {
  return courses.value
    .filter((course) => course.day === day && course.startWeek <= selectedWeek.value && course.endWeek >= selectedWeek.value)
    .sort((a, b) => a.start.localeCompare(b.start));
}

function normalizeCourseWeeks(course: Course) {
  const startWeek = clampWeek(Number(course.startWeek) || selectedWeek.value);
  const endWeek = clampWeek(Number(course.endWeek) || startWeek);
  return {
    ...course,
    startWeek: Math.min(startWeek, endWeek),
    endWeek: Math.max(startWeek, endWeek)
  };
}

function clampWeek(value: number) {
  return Math.min(maxTermWeek, Math.max(1, Math.round(value)));
}

function clampNumber(value: number, min: number, max: number) {
  if (min > max) return (min + max) / 2;
  return Math.min(max, Math.max(min, value));
}

function nextCourseId() {
  const nextId = Math.max(courseIdSeed.value, ...courses.value.map((course) => course.id)) + 1;
  courseIdSeed.value = nextId;
  return nextId;
}

function weekRangeLabel(course: Course) {
  return course.startWeek === course.endWeek ? `第 ${course.startWeek} 周` : `${course.startWeek}-${course.endWeek} 周`;
}

function boardMatches(post: CampusPost) {
  return activeBoard.value === 'recommend' ? true : post.board === activeBoard.value;
}

function postsForDate(dateKey: string) {
  return posts.value
    .filter((post) => postDateKey(post) === dateKey && boardMatches(post))
    .sort((a, b) => recommendScore(b) - recommendScore(a));
}

function boardName(board: BoardCode) {
  return boards.value.find((item) => item.code === board)?.name || '推荐';
}

function roleLabel(post: CampusPost) {
  if (post.author.role === 'SCHOOL_OPERATOR') return '校霸';
  if (post.author.role === 'COLLEGE_OPERATOR') return '院花';
  if (post.author.role === 'MAJOR_OPERATOR') return '级草';
  if (post.author.role === 'ADMIN') return '管理员';
  return post.official ? '官方 PO' : '同学';
}

function deadlineText(value?: string) {
  return value ? `${dayjs(value).format('M月D日 HH:mm')} 截止` : '今天保持关注';
}

function formatPublishedAt(value: string) {
  const date = dayjs(value);
  return date.year() === dayjs().year() ? date.format('MM/DD HH:mm') : date.format('YYYY/MM/DD HH:mm');
}

function compactPostTitle(title: string) {
  const compact = title.replace(/\s+/g, '');
  return compact.length > 16 ? compact.slice(0, 16) : compact;
}

function stripProfileMotto(bio?: string) {
  return (bio || '').replace(/\n?座右铭：.{0,30}$/u, '').trim();
}

function extractProfileMotto(bio?: string) {
  const match = (bio || '').match(/座右铭：(.{1,30})$/u);
  return match?.[1]?.trim() || '今天也要把有用的事说清楚';
}

function composeProfileBio(bio?: string, motto?: string) {
  const cleanBio = (bio || '').trim().slice(0, 120);
  const cleanMotto = (motto || '').trim().slice(0, 30);
  return cleanMotto ? [cleanBio, `座右铭：${cleanMotto}`].filter(Boolean).join('\n') : cleanBio;
}

function levelBadgeClass(level: number) {
  return `level-nameplate--tier-${levelTier(level)}`;
}

function levelTitleFor(level: number) {
  return levelTiers[levelTier(level) - 1];
}

function levelTier(level: number) {
  return Math.min(10, Math.max(1, Math.ceil(level / 10)));
}

function postDateKey(post: CampusPost) {
  return dayjs(post.eventDate || post.deadlineAt || post.publishedAt).format('YYYY-MM-DD');
}

function inlineImageUrls(content: string) {
  const markdownUrls = Array.from(content.matchAll(/!\[[^\]]*]\(([^)]+)\)/g)).map((match) => match[1]);
  const htmlUrls = Array.from(content.matchAll(/<img[^>]+src=["']([^"']+)["'][^>]*>/gi)).map((match) => match[1]);
  return [...markdownUrls, ...htmlUrls];
}

function plainPostContent(content: string) {
  const htmlAsText = content
    .replace(/<img[^>]*>/gi, '')
    .replace(/<br\s*\/?>/gi, '\n')
    .replace(/<\/(div|p|li|ul|ol|h[1-6])>/gi, '\n')
    .replace(/<[^>]+>/g, '');
  const textarea = document.createElement('textarea');
  textarea.innerHTML = htmlAsText;
  return textarea.value
    .replace(/!\[[^\]]*]\([^)]+\)/g, '')
    .replace(/\*\*(.*?)\*\*/g, '$1')
    .replace(/\[size=(large|small)](.*?)\[\/size]/g, '$2')
    .replace(/\[color=#[0-9a-fA-F]{3,8}](.*?)\[\/color]/g, '$1')
    .trim();
}

function escapeHtml(content: string) {
  return content
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function sanitizeRichHtml(content: string) {
  const template = document.createElement('template');
  template.innerHTML = content;
  normalizeRichEditorMarkup(template.content);
  const allowedTags = new Set(['B', 'STRONG', 'I', 'EM', 'U', 'BR', 'DIV', 'P', 'SPAN', 'UL', 'OL', 'LI', 'IMG']);
  template.content.querySelectorAll('*').forEach((node) => {
    if (!allowedTags.has(node.tagName)) {
      node.replaceWith(document.createTextNode(node.textContent || ''));
      return;
    }
    Array.from(node.attributes).forEach((attr) => {
      const name = attr.name.toLowerCase();
      if (node.tagName === 'IMG' && name === 'src') return;
      if (node.tagName === 'IMG' && name === 'alt') return;
      if (node.tagName === 'SPAN' && name === 'class' && ['rich-post-content__large', 'rich-post-content__small'].includes(attr.value)) return;
      if (node.tagName === 'SPAN' && name === 'style') {
        const color = attr.value.match(/(?:^|;)\s*color:\s*(#[0-9a-fA-F]{3,8}|rgb\(\s*\d{1,3}\s*,\s*\d{1,3}\s*,\s*\d{1,3}\s*\))/i)?.[1];
        if (color) {
          node.setAttribute('style', `color: ${color}`);
          return;
        }
      }
      node.removeAttribute(attr.name);
    });
  });
  return template.innerHTML;
}

function renderInlineRichText(content: string) {
  return escapeHtml(content)
    .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
    .replace(/\[size=large](.*?)\[\/size]/g, '<span class="rich-post-content__large">$1</span>')
    .replace(/\[size=small](.*?)\[\/size]/g, '<span class="rich-post-content__small">$1</span>')
    .replace(/\[color=(#[0-9a-fA-F]{3,8})](.*?)\[\/color]/g, '<span style="color: $1">$2</span>');
}

function renderRichPostContent(content: string) {
  if (/<[a-z][\s\S]*>/i.test(content)) return sanitizeRichHtml(content);
  return content
    .split(/!\[[^\]]*]\([^)]+\)/g)
    .map((part) => renderInlineRichText(part))
    .join('')
    .replace(/\n/g, '<br>');
}

function recommendScore(post: CampusPost) {
  const hours = Math.max(1, dayjs().diff(dayjs(post.publishedAt), 'hour', true));
  const officialBoost = post.official ? 60 : 0;
  const pinnedBoost = post.pinned ? 80 : 0;
  return post.likeCount * 1.2 + post.commentCount * 4 + post.favoriteCount * 1.5 + post.shareCount * 2 + officialBoost + pinnedBoost - hours * 6;
}

function hotScore(post: CampusPost) {
  return (post.last2hLikes || Math.round(post.likeCount * 0.38)) + (post.last2hComments || Math.round(post.commentCount * 0.48)) * 4 + post.shareCount * 0.5;
}

function featuredCommentFor(post: CampusPost) {
  const featured = commentsForPost(post).slice().sort((a, b) => b.likeCount - a.likeCount)[0];
  return featured ? `${featured.author.nickname}：${featured.content}` : '还没有热评，抢第一个补充信息。';
}

function interactionNoticeText(actorName: string, notice: InteractionNoticeView) {
  if (notice.type === 'like') return `${actorName} 点赞了你的 PO`;
  if (notice.type === 'favorite') return `${actorName} 收藏了你的 PO`;
  if (notice.type === 'reply') return `${actorName} 给你的评论回复：${(notice.commentContent || '').trim()}`;
  return `${actorName} 给你评论：${(notice.commentContent || '').trim()}`;
}

function commentsForPost(post: CampusPost) {
  return comments.value.filter((comment) => comment.postId === post.id);
}

function xpForLevel(level: number) {
  if (level <= 30) return 45;
  if (level <= 60) return 55;
  if (level <= 85) return 65;
  return 75;
}

function totalXpForLevel(level: number) {
  const completed = Math.max(0, level - 1);
  return Math.round(completed * completed * 28 + completed * 520);
}

function levelXpNeed(level: number) {
  return totalXpForLevel(level + 1) - totalXpForLevel(level);
}

function fallbackAvatar(name: string) {
  return `https://api.dicebear.com/8.x/initials/svg?seed=${encodeURIComponent(name || 'PO')}`;
}

function fallbackCover(id: number) {
  const covers = [
    'https://images.unsplash.com/photo-1521587760476-6c12a4b040da?auto=format&fit=crop&w=900&q=80',
    'https://images.unsplash.com/photo-1434030216411-0b793f4b4173?auto=format&fit=crop&w=900&q=80',
    'https://images.unsplash.com/photo-1497366754035-f200968a6e72?auto=format&fit=crop&w=900&q=80',
    'https://images.unsplash.com/photo-1515879218367-8466d910aaa4?auto=format&fit=crop&w=900&q=80'
  ];
  return covers[Math.abs(id) % covers.length];
}

async function safe<T>(fn: () => Promise<T>, fallback: T) {
  try {
    return await fn();
  } catch {
    return fallback;
  }
}

function enrichApiPosts(list: PostView[]): CampusPost[] {
  return list.map((post, index) => ({
    ...post,
    board: post.board === 'recommend' ? 'school' : post.board,
    eventDate: post.deadlineAt || post.publishedAt,
    last2hLikes: Math.round(post.likeCount * (0.32 + index * 0.02)),
    last2hComments: Math.round(post.commentCount * (0.4 + index * 0.01))
  }));
}

function normalizeBoards(list: BoardView[]) {
  const fallback = fallbackBoards();
  return fallback.map((board) => list.find((item) => item.code === board.code) || board);
}

function authorFromUser(user: UserProfile) {
  return {
    id: user.id,
    uid: user.publicUid,
    nickname: user.nickname,
    avatarUrl: user.avatarUrl,
    role: user.role,
    operatorScope: user.operatorScope,
    level: user.level,
    levelTitle: user.levelTitle,
    school: '试点大学',
    college: user.college,
    major: user.major,
    grade: user.grade,
    online: user.online
  };
}

function publicProfileFromAuthor(author: AuthorView): PublicProfileView {
  const profile: UserProfile = {
    id: author.id || 0,
    publicUid: author.uid,
    email: '',
    nickname: author.nickname,
    avatarUrl: author.avatarUrl,
    college: author.college,
    major: author.major,
    grade: author.grade,
    verifiedStatus: 'verified',
    role: author.role,
    operatorScope: author.operatorScope,
    level: author.level,
    xp: author.level * 40,
    levelTitle: author.levelTitle,
    status: 'active',
    online: author.online
  };
  return {
    profile,
    stats: {
      favorites: 0,
      likedPosts: 0,
      sharedPosts: 0,
      completed: 0,
      comments: comments.value.filter((comment) => comment.author.uid === author.uid).length,
      upcoming: 0,
      avoidedRisks: 0,
      following: 0,
      followers: 0,
      posts: posts.value.filter((post) => post.author.uid === author.uid).length
    },
    following: false,
    mine: isAuthenticated.value && currentUser.value.publicUid === author.uid
  };
}

function fallbackUser(): UserProfile {
  return {
    id: 0,
    publicUid: '00000000',
    email: '',
    nickname: '未登录',
    avatarUrl: '',
    verifiedStatus: 'guest',
    role: 'USER',
    operatorScope: 'student',
    level: 1,
    xp: 0,
    levelTitle: '萌新探路员',
    status: 'guest',
    online: false
  };
}

function fallbackStats(): UserStats {
  return { favorites: 0, likedPosts: 0, sharedPosts: 0, completed: 0, comments: 0, upcoming: 0, avoidedRisks: 0, following: 0, followers: 0, posts: 0 };
}

function fallbackBoards(): BoardView[] {
  return [
    { code: 'recommend', name: '推荐', description: '按热度、官方权重和发布时间混排', count: 128 },
    { code: 'school', name: '校 PO', description: '全校同学都能看到的校园动态', count: 42 },
    { code: 'college', name: '院 PO', description: '同学院范围内更贴近课程和活动', count: 31 },
    { code: 'major', name: '专业 PO', description: '专业相关经验、作业、竞赛和提醒', count: 24 }
  ];
}

function fallbackPosts(): CampusPost[] {
  const now = dayjs();
  const student = {
    id: 2,
    uid: '24052001',
    nickname: '小坡不鸽',
    avatarUrl: 'https://api.dicebear.com/8.x/initials/svg?seed=PO',
    role: 'USER' as const,
    operatorScope: 'student',
    level: 64,
    levelTitle: '校园情报员',
    school: '试点大学',
    college: '计算机学院',
    major: '计算机科学与技术',
    grade: '大三'
  };
  const schoolOperator = {
    ...student,
    id: 3,
    uid: '24052002',
    nickname: '校霸情报台',
    avatarUrl: 'https://api.dicebear.com/8.x/initials/svg?seed=XB',
    role: 'SCHOOL_OPERATOR' as const,
    operatorScope: 'school',
    level: 88,
    levelTitle: '毕设渡劫人'
  };
  const collegeOperator = {
    ...student,
    id: 4,
    uid: '24052003',
    nickname: '计院院花',
    avatarUrl: 'https://api.dicebear.com/8.x/initials/svg?seed=YH',
    role: 'COLLEGE_OPERATOR' as const,
    operatorScope: 'college',
    level: 72,
    levelTitle: '劳模舍友'
  };

  return [
    {
      id: 101,
      board: 'school',
      sourceType: 'official',
      title: '四六级报名明天 17:00 截止，没缴费就等于没报名',
      content: '这条是校霸提醒：系统显示已报名不代表缴费成功。\n今天先确认科目、考点、缴费状态，最好截图留存。去年就有人卡在“待支付”没发现，最后只能等下一轮。',
      excerpt: '系统显示已报名不代表缴费成功。今天先确认科目、考点、缴费状态，最好截图留存。',
      coverUrl: 'https://images.unsplash.com/photo-1434030216411-0b793f4b4173?auto=format&fit=crop&w=900&q=80',
      images: [],
      riskLevel: 'high',
      official: true,
      dontMiss: true,
      pinned: true,
      deadlineAt: now.add(1, 'day').hour(17).minute(0).toISOString(),
      eventDate: now.add(1, 'day').format('YYYY-MM-DD'),
      missConsequence: '错过后通常不能补报，只能等下一轮。',
      nextAction: '打开报名系统，确认缴费状态和考点信息。',
      tags: ['今日校园PO', '官方', '考试'],
      likeCount: 286,
      commentCount: 42,
      favoriteCount: 73,
      shareCount: 31,
      liked: false,
      favorited: true,
      status: 'published',
      publishedAt: now.minute(13).second(0).toISOString(),
      createdAt: now.toISOString(),
      author: schoolOperator,
      last2hLikes: 251,
      last2hComments: 38
    },
    {
      id: 102,
      board: 'college',
      sourceType: 'student',
      title: '计院项目实训组队墙开了，别等 ddl 才找队友',
      content: '学院今年把项目实训提前到了第 12 周确认队伍。现在已经有人在墙上写技术栈、可用时间和想做方向了，别最后只剩随机匹配。',
      excerpt: '学院今年把项目实训提前到了第 12 周确认队伍。现在已经有人在墙上写技术栈和想做方向。',
      coverUrl: 'https://images.unsplash.com/photo-1519389950473-47ba0277781c?auto=format&fit=crop&w=900&q=80',
      images: [],
      riskLevel: 'normal',
      official: false,
      dontMiss: true,
      pinned: false,
      deadlineAt: now.add(6, 'day').hour(15).minute(59).toISOString(),
      eventDate: now.add(6, 'day').format('YYYY-MM-DD'),
      tags: ['计院', '组队', '项目实训'],
      likeCount: 198,
      commentCount: 57,
      favoriteCount: 66,
      shareCount: 29,
      liked: false,
      favorited: false,
      status: 'published',
      publishedAt: now.subtract(18, 'minute').toISOString(),
      createdAt: now.subtract(18, 'minute').toISOString(),
      author: collegeOperator,
      last2hLikes: 172,
      last2hComments: 49
    },
    {
      id: 103,
      board: 'major',
      sourceType: 'student',
      title: '数据结构实验报告别只写代码，老师真的会看复杂度分析',
      content: '上周刚被退回一次。建议每个算法后面都补时间复杂度、边界输入和截图，查重也会更稳一点。尤其是图那一章，别只贴运行结果。',
      excerpt: '建议每个算法后面都补时间复杂度、边界输入和截图，查重也会更稳一点。',
      coverUrl: 'https://images.unsplash.com/photo-1515879218367-8466d910aaa4?auto=format&fit=crop&w=900&q=80',
      images: [],
      riskLevel: 'normal',
      official: false,
      dontMiss: false,
      pinned: false,
      deadlineAt: now.add(3, 'day').toISOString(),
      eventDate: now.add(3, 'day').format('YYYY-MM-DD'),
      tags: ['专业PO', '数据结构', '实验报告'],
      likeCount: 321,
      commentCount: 58,
      favoriteCount: 66,
      shareCount: 44,
      liked: true,
      favorited: true,
      status: 'published',
      publishedAt: now.subtract(33, 'minute').toISOString(),
      createdAt: now.subtract(33, 'minute').toISOString(),
      author: student,
      last2hLikes: 210,
      last2hComments: 44
    },
    {
      id: 104,
      board: 'school',
      sourceType: 'student',
      title: '图书馆三楼靠窗位今天真的像开了专注结界',
      content: '早八之后去图书馆三楼，靠西侧窗户那一排插座都能用，旁边还不会被阳光直晒。期中周想找安静位置的同学可以冲。',
      excerpt: '早八之后去图书馆三楼，靠西侧窗户那一排插座都能用，旁边还不会被阳光直晒。',
      coverUrl: 'https://images.unsplash.com/photo-1521587760476-6c12a4b040da?auto=format&fit=crop&w=900&q=80',
      images: [],
      riskLevel: 'normal',
      official: false,
      dontMiss: false,
      pinned: false,
      tags: ['图书馆', '自习', '期中周'],
      likeCount: 428,
      commentCount: 96,
      favoriteCount: 81,
      shareCount: 47,
      liked: false,
      favorited: false,
      status: 'published',
      publishedAt: now.subtract(52, 'minute').toISOString(),
      createdAt: now.subtract(52, 'minute').toISOString(),
      author: student,
      last2hLikes: 233,
      last2hComments: 61
    },
    {
      id: 105,
      board: 'school',
      sourceType: 'official',
      title: '校园卡系统今晚 23:30 维护，提前充值别卡在夜宵窗口',
      content: '信息办通知今晚校园卡系统短暂停服。宿舍水电、食堂小额支付可能会受影响，建议晚饭前确认余额。',
      excerpt: '信息办通知今晚校园卡系统短暂停服。宿舍水电、食堂小额支付可能会受影响。',
      coverUrl: 'https://images.unsplash.com/photo-1556745757-8d76bdb6984b?auto=format&fit=crop&w=900&q=80',
      images: [],
      riskLevel: 'medium',
      official: true,
      dontMiss: true,
      pinned: false,
      deadlineAt: now.hour(23).minute(30).toISOString(),
      eventDate: now.format('YYYY-MM-DD'),
      tags: ['今日校园PO', '校园卡', '维护'],
      likeCount: 176,
      commentCount: 35,
      favoriteCount: 29,
      shareCount: 22,
      liked: false,
      favorited: false,
      status: 'published',
      publishedAt: now.subtract(1, 'hour').toISOString(),
      createdAt: now.subtract(1, 'hour').toISOString(),
      author: schoolOperator,
      last2hLikes: 119,
      last2hComments: 31
    },
    {
      id: 106,
      board: 'college',
      sourceType: 'student',
      title: '计院 403 机房鼠标别碰 12 号位，滚轮已经失灵',
      content: '下午上机课踩坑，12 号位鼠标滚轮完全不动。老师说已经报修，明天如果还没换，建议提前找助教登记。',
      excerpt: '下午上机课踩坑，12 号位鼠标滚轮完全不动。老师说已经报修。',
      coverUrl: 'https://images.unsplash.com/photo-1516321318423-f06f85e504b3?auto=format&fit=crop&w=900&q=80',
      images: [],
      riskLevel: 'normal',
      official: false,
      dontMiss: false,
      pinned: false,
      tags: ['机房', '上机课', '计院'],
      likeCount: 143,
      commentCount: 64,
      favoriteCount: 18,
      shareCount: 11,
      liked: false,
      favorited: false,
      status: 'published',
      publishedAt: now.subtract(72, 'minute').toISOString(),
      createdAt: now.subtract(72, 'minute').toISOString(),
      author: student,
      last2hLikes: 97,
      last2hComments: 51
    },
    {
      id: 107,
      board: 'major',
      sourceType: 'student',
      title: '操作系统小测范围出来了，PV 操作和死锁一定要刷',
      content: '老师今天课后口头说了范围，选择题会卡概念，简答重点是 PV 操作、银行家算法和死锁必要条件。别只看 PPT 最后一页。',
      excerpt: '选择题会卡概念，简答重点是 PV 操作、银行家算法和死锁必要条件。',
      coverUrl: 'https://images.unsplash.com/photo-1516321497487-e288fb19713f?auto=format&fit=crop&w=900&q=80',
      images: [],
      riskLevel: 'medium',
      official: false,
      dontMiss: false,
      pinned: false,
      deadlineAt: now.add(2, 'day').hour(8).minute(0).toISOString(),
      eventDate: now.add(2, 'day').format('YYYY-MM-DD'),
      tags: ['操作系统', '小测', '复习'],
      likeCount: 268,
      commentCount: 41,
      favoriteCount: 94,
      shareCount: 38,
      liked: false,
      favorited: false,
      status: 'published',
      publishedAt: now.subtract(90, 'minute').toISOString(),
      createdAt: now.subtract(90, 'minute').toISOString(),
      author: student,
      last2hLikes: 141,
      last2hComments: 34
    },
    {
      id: 108,
      board: 'school',
      sourceType: 'student',
      title: '南门打印店今天排队短，论文胶装可以现在去',
      content: '刚从南门回来，三台机器都开着，胶装大概 15 分钟一份。明天下午毕业生会集中，建议今天能搞完就搞完。',
      excerpt: '刚从南门回来，三台机器都开着，胶装大概 15 分钟一份。',
      coverUrl: 'https://images.unsplash.com/photo-1497366754035-f200968a6e72?auto=format&fit=crop&w=900&q=80',
      images: [],
      riskLevel: 'normal',
      official: false,
      dontMiss: false,
      pinned: false,
      tags: ['打印店', '论文', '南门'],
      likeCount: 154,
      commentCount: 22,
      favoriteCount: 35,
      shareCount: 18,
      liked: false,
      favorited: false,
      status: 'published',
      publishedAt: now.subtract(115, 'minute').toISOString(),
      createdAt: now.subtract(115, 'minute').toISOString(),
      author: student,
      last2hLikes: 83,
      last2hComments: 16
    },
    {
      id: 109,
      board: 'school',
      sourceType: 'official',
      title: '明天 15:00 学生活动中心有保研政策说明会',
      content: '教务处和各学院教务老师会在现场答疑，重点讲排名核算、竞赛加分和跨专业推免。座位有限，建议提前 20 分钟到。',
      excerpt: '重点讲排名核算、竞赛加分和跨专业推免。座位有限，建议提前 20 分钟到。',
      coverUrl: 'https://images.unsplash.com/photo-1515187029135-18ee286d815b?auto=format&fit=crop&w=900&q=80',
      images: [],
      riskLevel: 'normal',
      official: true,
      dontMiss: true,
      pinned: true,
      deadlineAt: now.add(1, 'day').hour(15).minute(0).toISOString(),
      eventDate: now.add(1, 'day').format('YYYY-MM-DD'),
      tags: ['今日校园PO', '保研', '说明会'],
      likeCount: 244,
      commentCount: 28,
      favoriteCount: 107,
      shareCount: 42,
      liked: false,
      favorited: false,
      status: 'published',
      publishedAt: now.subtract(3, 'hour').toISOString(),
      createdAt: now.subtract(3, 'hour').toISOString(),
      author: schoolOperator,
      last2hLikes: 71,
      last2hComments: 14
    },
    {
      id: 110,
      board: 'college',
      sourceType: 'official',
      title: '计院创新实践学分认定材料 5 月 7 日前交到辅导员处',
      content: '比赛证书、项目证明、论文录用截图都需要原件或可验证链接。材料不完整会退回，别最后一天才打包。',
      excerpt: '比赛证书、项目证明、论文录用截图都需要原件或可验证链接。',
      coverUrl: 'https://images.unsplash.com/photo-1450101499163-c8848c66ca85?auto=format&fit=crop&w=900&q=80',
      images: [],
      riskLevel: 'medium',
      official: true,
      dontMiss: true,
      pinned: false,
      deadlineAt: now.add(4, 'day').hour(17).minute(30).toISOString(),
      eventDate: now.add(4, 'day').format('YYYY-MM-DD'),
      tags: ['计院', '创新学分', '材料'],
      likeCount: 185,
      commentCount: 19,
      favoriteCount: 88,
      shareCount: 27,
      liked: false,
      favorited: false,
      status: 'published',
      publishedAt: now.subtract(4, 'hour').toISOString(),
      createdAt: now.subtract(4, 'hour').toISOString(),
      author: collegeOperator,
      last2hLikes: 42,
      last2hComments: 8
    }
  ];
}

function fallbackComments(): CampusComment[] {
  const posts = fallbackPosts();
  const student = posts[3].author;
  const schoolOperator = posts[0].author;
  const collegeOperator = posts[1].author;
  const now = dayjs();
  return [
    { id: 1, postId: 101, userId: 2, content: '补充：缴费截图一定要留，去年有人系统延迟显示很吓人。', likeCount: 88, replyCount: 3, liked: false, featured: true, status: 'visible', createdAt: now.subtract(12, 'minute').toISOString(), author: student },
    { id: 2, postId: 101, userId: 3, parentId: 1, content: '报名系统右上角的支付状态比短信更准，别只看短信。', likeCount: 61, replyCount: 0, liked: false, featured: false, status: 'visible', createdAt: now.subtract(10, 'minute').toISOString(), author: schoolOperator },
    { id: 3, postId: 101, userId: 4, parentId: 1, content: '手机端也能查，但截图建议电脑端截全一点。', likeCount: 24, replyCount: 0, liked: false, featured: false, status: 'visible', createdAt: now.subtract(8, 'minute').toISOString(), author: collegeOperator },
    { id: 4, postId: 102, userId: 4, content: '想做前端可视化方向的可以看我主页，缺一个会写接口的队友。', likeCount: 72, replyCount: 1, liked: false, featured: true, status: 'visible', createdAt: now.subtract(21, 'minute').toISOString(), author: collegeOperator },
    { id: 5, postId: 103, userId: 2, content: '复杂度那里最好写最坏情况，老师上次专门圈了。', likeCount: 69, replyCount: 0, liked: false, featured: true, status: 'visible', createdAt: now.subtract(29, 'minute').toISOString(), author: student },
    { id: 6, postId: 104, userId: 3, content: '这个点位我认证，下午两点以后靠近楼梯那边会吵一点。', likeCount: 31, replyCount: 7, liked: false, featured: true, status: 'visible', createdAt: now.subtract(37, 'minute').toISOString(), author: schoolOperator },
    { id: 7, postId: 104, userId: 2, parentId: 6, content: '靠窗第二排插座比较稳，第一排有两个口松了。', likeCount: 18, replyCount: 0, liked: false, featured: false, status: 'visible', createdAt: now.subtract(35, 'minute').toISOString(), author: student },
    { id: 8, postId: 104, userId: 4, parentId: 6, content: '上午十点之前人不多，下午基本满。', likeCount: 12, replyCount: 0, liked: false, featured: false, status: 'visible', createdAt: now.subtract(31, 'minute').toISOString(), author: collegeOperator },
    { id: 9, postId: 104, userId: 3, parentId: 6, content: '三楼饮水机今天也正常，带杯子就行。', likeCount: 9, replyCount: 0, liked: false, featured: false, status: 'visible', createdAt: now.subtract(28, 'minute').toISOString(), author: schoolOperator },
    { id: 10, postId: 104, userId: 2, parentId: 6, content: '靠电梯那侧会有人打电话，慎选。', likeCount: 7, replyCount: 0, liked: false, featured: false, status: 'visible', createdAt: now.subtract(25, 'minute').toISOString(), author: student },
    { id: 11, postId: 104, userId: 4, parentId: 6, content: '下午四点阳光会偏过来，电脑屏幕有点反光。', likeCount: 6, replyCount: 0, liked: false, featured: false, status: 'visible', createdAt: now.subtract(21, 'minute').toISOString(), author: collegeOperator },
    { id: 12, postId: 104, userId: 3, parentId: 6, content: '隔壁阅览室也有空位，但不能带咖啡。', likeCount: 4, replyCount: 0, liked: false, featured: false, status: 'visible', createdAt: now.subtract(18, 'minute').toISOString(), author: schoolOperator },
    { id: 13, postId: 104, userId: 2, parentId: 6, content: '感谢，刚去占到了一个靠窗位。', likeCount: 3, replyCount: 0, liked: false, featured: false, status: 'visible', createdAt: now.subtract(13, 'minute').toISOString(), author: student },
    { id: 14, postId: 105, userId: 3, content: '夜宵窗口能刷微信，但洗衣房还是校园卡，提前充最稳。', likeCount: 37, replyCount: 0, liked: false, featured: true, status: 'visible', createdAt: now.subtract(48, 'minute').toISOString(), author: schoolOperator },
    { id: 15, postId: 106, userId: 2, content: '12 号旁边 13 号也有点漂，建议直接坐后排。', likeCount: 40, replyCount: 0, liked: false, featured: true, status: 'visible', createdAt: now.subtract(1, 'hour').toISOString(), author: student },
    { id: 16, postId: 107, userId: 2, content: 'PV 那套题刷完课后题基本就够，银行家算法别算错 available。', likeCount: 46, replyCount: 0, liked: false, featured: true, status: 'visible', createdAt: now.subtract(1, 'hour').toISOString(), author: student },
    { id: 17, postId: 108, userId: 2, content: '刚去，A4 黑白还没涨价，胶装老板说今晚人不多。', likeCount: 29, replyCount: 0, liked: false, featured: true, status: 'visible', createdAt: now.subtract(1, 'hour').toISOString(), author: student },
    { id: 18, postId: 109, userId: 3, content: '跨专业推免问题建议现场问，线上群里答得比较保守。', likeCount: 33, replyCount: 0, liked: false, featured: true, status: 'visible', createdAt: now.subtract(2, 'hour').toISOString(), author: schoolOperator },
    { id: 19, postId: 110, userId: 4, content: '证书照片不行，要 PDF 或官网可查截图，辅导员今天刚强调。', likeCount: 31, replyCount: 0, liked: false, featured: true, status: 'visible', createdAt: now.subtract(3, 'hour').toISOString(), author: collegeOperator }
  ];
}

function fallbackConversations(): ConversationView[] {
  return [];
  const posts = fallbackPosts();
  const now = dayjs();
  return [
    {
      id: 1,
      peer: posts[3].author,
      lastMessage: '校霸情报台 关注了你',
      unreadCount: 1,
      lastMessageAt: now.subtract(12, 'minute').toISOString(),
      updatedAt: now.subtract(12, 'minute').toISOString()
    },
    {
      id: 2,
      peer: posts[1].author,
      lastMessage: '66666',
      unreadCount: 2,
      lastMessageAt: now.subtract(18, 'minute').toISOString(),
      updatedAt: now.subtract(18, 'minute').toISOString()
    }
  ];
}

function fallbackConversationMessages(): Record<number, CampusMessage[]> {
  return {};
  const posts = fallbackPosts();
  const now = dayjs();
  return {
    1: [
      {
        id: 1,
        conversationId: 1,
        senderId: posts[3].author.id,
        receiverId: fallbackUser().id,
        content: '校霸情报台 关注了你',
        read: false,
        createdAt: now.subtract(12, 'minute').toISOString()
      }
    ],
    2: [
      {
        id: 2,
        conversationId: 2,
        senderId: posts[1].author.id,
        receiverId: fallbackUser().id,
        content: '项目实训组队墙今晚会再整理一版。',
        read: true,
        createdAt: now.subtract(28, 'minute').toISOString()
      },
      {
        id: 3,
        conversationId: 2,
        senderId: fallbackUser().id,
        receiverId: posts[1].author.id,
        content: '我想报名前端可视化方向，还缺队友吗？',
        read: true,
        createdAt: now.subtract(24, 'minute').toISOString()
      },
      {
        id: 4,
        conversationId: 2,
        senderId: posts[1].author.id,
        receiverId: fallbackUser().id,
        content: 'good',
        read: false,
        createdAt: now.subtract(18, 'minute').toISOString()
      },
      {
        id: 5,
        conversationId: 2,
        senderId: posts[1].author.id,
        receiverId: fallbackUser().id,
        content: '66666',
        read: false,
        createdAt: now.subtract(17, 'minute').toISOString()
      }
    ]
  };
}

function fallbackCourses(): Course[] {
  return [
    seedCourse(1, 1, '数据结构', '08:30', '10:05', '理工楼 B204', '陈老师', '#e8f3ff', '专业通用', 1, 16),
    seedCourse(2, 1, '大学英语', '10:25', '12:00', '公教 310', '刘老师', '#fff2d8', '个人导入', 1, 16),
    seedCourse(3, 2, '操作系统', '08:30', '10:05', '计院 403', '周老师', '#eaf8ee', '专业通用', 1, 16),
    seedCourse(4, 2, '体育', '15:40', '17:15', '东操场', '王老师', '#fde9ec', '个人导入', 1, 12),
    seedCourse(5, 3, '数据库原理', '10:25', '12:00', '理工楼 A118', '孙老师', '#f0ecff', '专业通用', 1, 16),
    seedCourse(6, 3, '形势与政策', '14:00', '15:35', '公教 201', '赵老师', '#e8f3ff', '院花统一', 4, 8),
    seedCourse(7, 4, '计算机网络', '08:30', '10:05', '计院 305', '高老师', '#eaf8ee', '专业通用', 1, 16),
    seedCourse(8, 5, '项目实训', '14:00', '17:15', '创新实验室', '导师组', '#fff2d8', '院花统一', 5, 18),
    seedCourse(9, 6, '算法竞赛训练', '09:00', '11:30', '计院 502', 'ACM 队', '#f0ecff', '自选活动', 3, 18),
    seedCourse(10, 7, '小组周会', '19:00', '20:30', '线上会议', '项目组', '#fde9ec', '个人导入', 1, 20)
  ];
}

function seedCourse(id: number, day: number, title: string, start: string, end: string, location: string, teacher: string, color: string, scope: string, startWeek: number, endWeek: number): Course {
  return { id, day, title, start, end, location, teacher, color, scope, startWeek, endWeek, seriesId: id };
}
</script>
