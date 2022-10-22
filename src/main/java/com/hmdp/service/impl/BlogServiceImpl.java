package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.hmdp.utils.RedisConstants.BLOG_LIKED_KEY;

@Slf4j
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    @Transactional
    public Result getBlog(Long id) {
        Blog blog = getById(id);
        if(blog == null) return Result.fail("该博客不存在");

        //设置博客的作者信息
        User user = userService.getById(blog.getUserId());
        blog.setIcon(user.getIcon());
        blog.setName(user.getNickName());

        //对当前登录的用户是否已经对该博客点赞
        Double isLike = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + blog.getId(), UserHolder.getUser().getId().toString());
        blog.setIsLike(isLike != null);

        return Result.ok(blog);
    }

    @Override
    @Transactional
    public Result likeBlog(Long id) {
        //1、获取登录用户
        UserDTO user = UserHolder.getUser();
        Blog blog = getById(id);
        //2、判断当前用户是否点赞
        Double isLiked = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + blog.getId(), user.getId().toString());

        //3、如果未点赞
        if(isLiked == null){
            //3.1修改blog的like字段liked = liked + 1
            boolean isSuccess = update().setSql("liked = liked + 1").eq("id", id).update();
            //3.2将用户添加到redis中该blog的点赞集合中
            if(isSuccess){
                stringRedisTemplate.opsForZSet().add(BLOG_LIKED_KEY + blog.getId(), user.getId().toString(), System.currentTimeMillis());
            }
        }else{
            //4、如果已经点赞了，取消点赞
            //4.1将like字段-1
            update().setSql("liked = liked - 1").eq("id", id).update();
            //4.2将用户从set集合中移除
            stringRedisTemplate.opsForZSet().remove(BLOG_LIKED_KEY + blog.getId(), user.getId().toString());
        }

        return Result.ok();
    }

    @Override
    @Transactional
    public Result queryHotBlog(Integer current) {
        // 根据用户查询
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        // 获取当前页数据
        List<Blog> records = page.getRecords();
        // 查询用户
        records.forEach(blog ->{
            //博客的作者信息
            Long blogUserId = blog.getUserId();
            User user = userService.getById(blogUserId);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());

            //当前登录的用户是否对作品进行点赞
            UserDTO curUser = UserHolder.getUser();
            Double isLike = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + blog.getId(), curUser.getId().toString());
            //System.out.println(userId);
            //log.info("是否为set成员{}", isLike);
            blog.setIsLike(isLike != null);
        });
        return Result.ok(records);
    }

    @Override
    public Result getLikedList(Long blogId) {
        Blog blog = query().eq("id", blogId).one();
        //1、查询最近点赞的五个用户，使用zrange key 0 4
        Set<String> userSet = stringRedisTemplate.opsForZSet().range(BLOG_LIKED_KEY + blog.getId(), 0, 4);
        if(userSet == null || userSet.isEmpty()){
            return Result.ok(Collections.emptyList());
        }
        //2、解析出用户的id
        List<Long> ids = userSet.stream().map(Long::valueOf).collect(Collectors.toList());
        //3、根据用户的id查询用户的信息
        //注意此处，getByIds()方法用的where xxx in(x, x, x)来查询的，不能保证原有的顺序不变
        //List<User> users = userService.listByIds(ids);
        String idStr = StrUtil.join(",", ids);
        if(idStr == null) return Result.ok();
        List<UserDTO> likedList = userService.query()
                .in("id", ids)
                .last("order by field(id, "+idStr+")").list()
                .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        //4、返回用户的集合
        return Result.ok(likedList);
    }

    @Override
    public Result saveBlog(Blog blog) {

        // 获取登录用户
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        save(blog);
        //查询博客发送者所有的粉丝
        List<Follow> follows = followService.query().eq("user_id", user.getId()).list();

        //将博客信息推送给所有粉丝
        for (Follow follow : follows) {
            stringRedisTemplate.opsForZSet().add("feed:" + follow.getFollowUserId(), blog.getId().toString(), System.currentTimeMillis());
        }


        // 返回id
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //1、获取当前用户
        UserDTO user = UserHolder.getUser();

        //2、在redis中查询当前用户的收信箱
        String key = "feed:" + user.getId();
        Set<ZSetOperations.TypedTuple<String>> typedTuples =
                stringRedisTemplate.opsForZSet().reverseRangeByScoreWithScores(key, 0, max, offset, 3);

        //3、解析数据blogId、minTime、offset
        long minTime = 0;
        int os = 1;
        List<Long> ids = new ArrayList<>(typedTuples.size());  //查询出的博客的id
        for (ZSetOperations.TypedTuple<String> typedTuple : typedTuples) {
            //获取时间戳和博客id
            long time = typedTuple.getScore().longValue();
            String blogId = typedTuple.getValue();
            ids.add(Long.valueOf(blogId));

            //获取查询中minTime重复的值作为下一轮的offset
            if(time == minTime){
                os++;
            }else{
                minTime = time;
                os = 1;
            }
        }
        if(ids == null || ids.isEmpty()) return Result.ok();
        //4、根据blogId批量查询blog
        String idsStr = StrUtil.join(",", ids);
        List<Blog> blogs = query().in("id", ids).last("order by field(id, " + idsStr + ")").list();

        //5、需要将博客的点赞信息进行完善
        for (Blog blog : blogs) {
            //博客作者的信息
            User blogUser = userService.getById(blog.getId());
            blog.setIcon(blogUser.getIcon());
            blog.setName(blogUser.getNickName());

            //对当前登录的用户是否已经对该博客点赞
            Double isLike = stringRedisTemplate.opsForZSet().score(BLOG_LIKED_KEY + blog.getId(), UserHolder.getUser().getId().toString());
            blog.setIsLike(isLike != null);
        }

        //6、将blog封装并返回
        ScrollResult scrollResult = new ScrollResult();
        scrollResult.setList(blogs);
        scrollResult.setMinTime(minTime);
        scrollResult.setOffset(os);
        return Result.ok(scrollResult);
    }
}
