// 搜索提交逻辑只在打包脚本里，HTML 里没有任何 action
document.addEventListener('DOMContentLoaded', function () {
    var box = document.getElementById('q');
    document.getElementById('go').addEventListener('click', function () {
        window.location.href = '/ss2?kw=' + encodeURIComponent(box.value);
    });
});
